package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.ServiceConnectionLostException
import eu.darken.butler.common.files.saf.MissingUriPermissionException
import eu.darken.butler.common.files.write.FileCommitContext
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException

/**
 * Verifies the buffer's reaction to a backing file that vanishes mid-session: it latches a
 * read-only [DocumentBuffer.isBackingLost] state, still fails edits that need original bytes,
 * and leaves the piece table consistent after a failed edit.
 */
class DocumentBufferBackingLostTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val path: APath<*> = LocalPath.build("/tmp/mergetest.txt")

    /** [EditorDataSource] that serves [text] until [failWith] is set, then throws on every read. */
    private class FailableDataSource(
        private val text: String,
        private val path: APath<*>,
    ) : EditorDataSource {
        var failWith: (() -> Throwable)? = null
        private val bytes = text.toByteArray(Charsets.UTF_8)
        private val _contentSource = MutableStateFlow<ContentSource>(ContentSource.Memory(size = 0L))
        override val contentSource: StateFlow<ContentSource> = _contentSource.asStateFlow()

        override suspend fun open() {
            _contentSource.value = ContentSource.File(
                path = path,
                size = bytes.size.toLong(),
                lastModified = null,
                canWrite = true,
                detectedCharset = Charsets.UTF_8,
            )
        }

        override suspend fun getSize(): Long = bytes.size.toLong()

        override suspend fun getMeta(): EditorDataSource.Meta {
            failWith?.let { throw it() }
            return EditorDataSource.Meta(size = bytes.size.toLong(), modifiedAt = null)
        }

        override suspend fun openByteSource(offset: Long): Source {
            failWith?.let { throw it() }
            return Buffer().write(bytes).apply { skip(offset) }
        }

        override suspend fun commit(writer: suspend (FileCommitContext) -> Unit) =
            throw UnsupportedOperationException("not needed for these tests")

        override suspend fun close() {
            _contentSource.value = ContentSource.Memory(size = 0L)
        }
    }

    private suspend fun createBuffer(content: String, source: FailableDataSource): DocumentBuffer {
        source.open()
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = source,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 16,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        return buffer
    }

    @Test
    fun `polled metadata failure latches read-only`() = runTest {
        val source = FailableDataSource("first line\nsecond line\n", path)
        val buffer = createBuffer("first line\nsecond line\n", source)
        buffer.isBackingLost.value shouldBe false

        source.failWith = { ReadException("Does not exist or can't be read :(", path) }
        buffer.checkExternalChange() shouldBe DocumentBuffer.ExternalChangeProbe.Unknown

        buffer.isBackingLost.value shouldBe true
        // The read-only state is published on the content source for the UI
        (buffer.contentSource.value as ContentSource.File).isBackingLost shouldBe true
    }

    @Test
    fun `edit needing original bytes fails and latches read-only`() = runTest {
        val source = FailableDataSource("first line\nsecond line\n", path)
        val buffer = createBuffer("first line\nsecond line\n", source)

        source.failWith = { FileNotFoundException("open failed: ENOENT (No such file or directory)") }
        // Splitting the single original piece mid-content needs charToByte -> reads the gone file
        val result = buffer.insertText(TextPosition(offset = 4L, line = 0, column = 4), text = "X")

        result.isFailure shouldBe true
        buffer.isBackingLost.value shouldBe true
    }

    @Test
    fun `failed mid-document edit leaves the piece table consistent`() = runTest {
        val source = FailableDataSource("hello world", path)
        val buffer = createBuffer("hello world", source)
        val originalLength = buffer.totalLength.value

        source.failWith = { ReadException("gone", path) }
        buffer.insertText(TextPosition(offset = 5L, line = 0, column = 5), text = "X").isFailure shouldBe true

        // An append needs no original bytes, so it still succeeds - and its invariant check (the
        // buffer runs with assertions on) would throw on any length drift left by the failed edit.
        val append = buffer.insertText(
            TextPosition(offset = originalLength, line = 0, column = originalLength.toInt()),
            text = "!",
        )
        append.isSuccess shouldBe true
        buffer.totalLength.value shouldBe originalLength + 1
    }

    @Test
    fun `replaceMatches leaves content unchanged when the backing file vanishes`() = runTest {
        val source = FailableDataSource("foo foo", path)
        val buffer = createBuffer("foo foo", source)
        // Warms the decode cache so replaceMatches' verify pass reads from memory; the mutation's
        // charToByte still reads the file directly and trips the failure.
        val original = buffer.getFullText().getOrThrow()

        source.failWith = { FileNotFoundException("open failed: ENOENT (No such file or directory)") }
        val result = buffer.replaceMatches(
            listOf(
                DocumentBuffer.MatchReplacement(startOffset = 0L, oldText = "foo", newText = "bar"),
                DocumentBuffer.MatchReplacement(startOffset = 4L, oldText = "foo", newText = "bar"),
            ),
        )

        result.isFailure shouldBe true
        buffer.getFullText().getOrThrow() shouldBe original
    }

    @Test
    fun `not-found and permission failures latch, transient service loss does not`() = runTest {
        suspend fun latchesFor(failure: () -> Throwable): Boolean {
            val source = FailableDataSource("alpha\nbeta\n", path)
            val buffer = createBuffer("alpha\nbeta\n", source)
            source.failWith = failure
            buffer.checkExternalChange()
            return buffer.isBackingLost.value
        }

        latchesFor { ReadException("Does not exist", path) } shouldBe true
        latchesFor { FileNotFoundException("ENOENT") } shouldBe true
        latchesFor { NoSuchFileException("/tmp/mergetest.txt") } shouldBe true
        // Matched by type, not by its message happening to contain "does not exist"
        latchesFor { PathNotFoundException(path) } shouldBe true
        latchesFor {
            PathPermissionDeniedException(path, "lookup", PathPermissionDeniedException.Reason.ACCESS_DENIED)
        } shouldBe true
        // SAF permission revocation: a ReadException subtype whose message has no not-found token
        latchesFor { MissingUriPermissionException(path = path) } shouldBe true

        // A dropped root/ADB service is transient - re-wrapping it in a ReadException must not latch
        latchesFor { ServiceConnectionLostException() } shouldBe false
        latchesFor { ReadException("read failed", path, cause = ServiceConnectionLostException()) } shouldBe false

        // A generic read failure with no not-found signal must NOT lock the file read-only forever
        latchesFor { ReadException("Couldn't open input stream", path) } shouldBe false
    }
}
