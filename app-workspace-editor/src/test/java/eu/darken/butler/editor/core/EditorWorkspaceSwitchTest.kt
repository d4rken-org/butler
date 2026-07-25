package eu.darken.butler.editor.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.EditorState as EngineState
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Swapping the file behind a tab must swap its whole identity at once. A switch publishes the new
 * content path synchronously while the new engine still has nothing to report, so the name next to
 * that path must never still be the previous file's.
 *
 * The tabs start as scratch buffers and are handed an engine that reports file A: that way the
 * observed identity ("a.txt") differs from the seed ("Untitled"), which is what proves the content
 * source observer really ran before the switch under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorWorkspaceSwitchTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fileA = LocalPath.build("/sdcard/Download/a.txt")
    private val fileB = LocalPath.build("/sdcard/Documents/b.txt")
    private val untitled get() = context.getString(R.string.editor_file_untitled)

    private fun fileSource(path: APath<*>, size: Long = 4L) =
        ContentSource.File(path = path, size = size, lastModified = null, canWrite = true)

    /** Engine that reports [path] as loaded, or an in-memory buffer when [path] is null. */
    private fun engineFor(
        path: APath<*>?,
        sources: MutableStateFlow<ContentSource> = MutableStateFlow(
            path?.let { fileSource(it) } ?: ContentSource.Memory(size = 0L)
        ),
        onInitialize: suspend () -> Result<Unit> = { Result.success(Unit) },
    ) = mockk<EditorEngine>(relaxed = true).apply {
        coEvery { initialize() } coAnswers { onInitialize() }
        every { contentSource } returns sources
        every { state } returns MutableStateFlow(
            when (path) {
                null -> EngineState.Empty
                else -> EngineState.Loaded(path, mockk(relaxed = true), sources.value, false)
            }
        )
        every { filePath } returns path
    }

    private fun makeWorkspace(vararg engines: EditorEngine) = EditorWorkspace(
        id = Workspace.Id(),
        creationArguments = EditorArguments.Default(),
        gatewaySwitch = mockk(relaxed = true),
        editorEngineFactory = mockk<EditorEngine.Factory>(relaxed = true).apply {
            every { create(any(), any(), any(), any()) } returnsMany engines.toList()
        },
        editorSettings = mockk(relaxed = true),
        operationsManager = mockk<OperationsManager>(relaxed = true).apply {
            every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
        },
        pasteFileReader = mockk(relaxed = true),
    )

    /** Waits until the engine's report of file A has reached the tab identity. */
    private suspend fun EditorWorkspace.awaitFileAReported() =
        withTimeout(10.seconds) { info.first { it.title.get(context) == "a.txt" } }

    @Test
    fun `opening another file never pairs the new path with the old name`() = runBlocking {
        // The second engine hangs in initialize(), holding the workspace in exactly the state the
        // switch publishes synchronously
        val opening = CompletableDeferred<Result<Unit>>()
        val workspace = makeWorkspace(engineFor(fileA), engineFor(fileB, onInitialize = { opening.await() }))
        workspace.awaitFileAReported()

        val open: Job = launch(Dispatchers.Default) { workspace.openFile(fileB) }
        val switched = withTimeout(10.seconds) { workspace.info.first { it.contentPath == fileB } }

        switched.title.get(context) shouldBe "b.txt"
        switched.subtitle!!.get(context) shouldBe "/sdcard/Documents"
        opening.complete(Result.success(Unit))
        open.join()
        workspace.release()
    }

    @Test
    fun `closing the file drops the name with the path`() = runBlocking {
        val closing = CompletableDeferred<Result<Unit>>()
        val workspace = makeWorkspace(engineFor(fileA), engineFor(null, onInitialize = { closing.await() }))
        workspace.awaitFileAReported()

        val close: Job = launch(Dispatchers.Default) { workspace.closeFile() }
        val closed = withTimeout(10.seconds) { workspace.info.first { it.title.get(context) != "a.txt" } }

        closed.contentPath shouldBe null
        closed.title.get(context) shouldBe untitled
        closed.subtitle shouldBe null
        closing.complete(Result.success(Unit))
        close.join()
        workspace.release()
    }

    @Test
    fun `a failed switch rolls the identity back to the file that is still open`() = runBlocking {
        val boom = IOException("Permission denied")
        val workspace = makeWorkspace(engineFor(fileA), engineFor(fileB, onInitialize = { Result.failure(boom) }))
        workspace.awaitFileAReported()

        shouldThrow<IOException> { workspace.openFile(fileB) }

        // The rollback publishes synchronously, so this is the state it left behind
        val rolledBack = workspace.info.value
        rolledBack.contentPath shouldBe fileA
        rolledBack.title.get(context) shouldBe "a.txt"
        rolledBack.subtitle!!.get(context) shouldBe "/sdcard/Download"
        workspace.release()
    }

    @Test
    fun `a late report from the replaced engine cannot rename the tab back`() = runBlocking {
        val sourcesA = MutableStateFlow<ContentSource>(fileSource(fileA))
        val workspace = makeWorkspace(engineFor(fileA, sources = sourcesA), engineFor(fileB))
        workspace.awaitFileAReported()

        workspace.openFile(fileB)
        sourcesA.value = fileSource(fileA, size = 8L)

        val current = withTimeout(10.seconds) { workspace.info.first { it.contentPath == fileB } }
        current.title.get(context) shouldBe "b.txt"
        current.subtitle!!.get(context) shouldBe "/sdcard/Documents"
        workspace.release()
    }
}
