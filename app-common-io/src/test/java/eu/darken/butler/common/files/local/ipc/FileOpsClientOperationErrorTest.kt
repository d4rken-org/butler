package eu.darken.butler.common.files.local.ipc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.ipc.IpcErrorCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException

/**
 * How the delete/copy/move streams report a failed operation: the error event carries an
 * [IpcErrorCodec] payload the client rebuilds by type, `cancelled` is the only cancellation signal,
 * and a stream ending without a terminal event is truncation rather than success. The connection is
 * mocked with a real [toRemoteInputStream] pipe, and the host round trips drive a [FileOpsHost] over
 * a mocked backend so both ends of the protocol are covered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class FileOpsClientOperationErrorTest : BaseTest() {

    private val target = LocalPath.build("/data/subtree/target.txt")
    private val destination = LocalPath.build("/data/other")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection = mockk<FileOpsConnection>()
    private val client = FileOpsClient(connection)
    private val hostOps = mockk<LocalFileSystemOps>()

    private fun denial(operation: String) = PathPermissionDeniedException(
        path = target,
        operation = operation,
        reason = PathPermissionDeniedException.Reason.NOT_PERMITTED,
    )

    private fun host() = FileOpsHost(
        context = ApplicationProvider.getApplicationContext<Context>(),
        appScope = scope,
        dispatcherProvider = TestDispatcherProvider(Dispatchers.IO),
        fileSystemOps = hostOps,
    )

    @After
    fun teardown() {
        scope.cancel()
    }

    private fun lookup(path: LocalPath) = LocalPathLookup(
        lookedUp = path,
        fileType = FileType.FILE,
        size = 0L,
        modifiedAt = null,
    )

    private fun deleteStreams(vararg events: DeleteOperationEvent) {
        every { connection.deleteStream(any(), any(), any(), any()) } answers {
            events.asList().asFlow().toRemoteInputStream(scope)
        }
    }

    private fun copyStreams(vararg events: CopyOperationEvent) {
        every { connection.copyStream(any(), any(), any(), any(), any(), any()) } answers {
            events.asList().asFlow().toRemoteInputStream(scope)
        }
    }

    private fun moveStreams(vararg events: MoveOperationEvent) {
        every { connection.moveStream(any(), any(), any(), any(), any(), any()) } answers {
            events.asList().asFlow().toRemoteInputStream(scope)
        }
    }

    private suspend fun collectDelete() = withTimeout(10_000) {
        client.delete(setOf(target), DeleteAction.Options(recursive = true)).toList()
    }

    private suspend fun collectCopy() = withTimeout(10_000) {
        client.copy(setOf(target), destination, null, CopyAction.Options()).toList()
    }

    private suspend fun collectMove() = withTimeout(10_000) {
        client.move(setOf(target), destination, null, MoveAction.Options()).toList()
    }

    @Test
    fun `an encoded delete error surfaces as the host type`() {
        deleteStreams(DeleteOperationEvent.Error(IpcErrorCodec.encode(denial("delete")), cancelled = false))

        runBlocking {
            val thrown = shouldThrow<PathPermissionDeniedException> { collectDelete() }
            thrown.path!!.path shouldBe target.path
            thrown.operation shouldBe "delete"
        }
    }

    @Test
    fun `a markerless delete error surfaces as an IOException with the host message`() {
        deleteStreams(DeleteOperationEvent.Error("Backend went away", cancelled = false))

        runBlocking {
            shouldThrow<IOException> { collectDelete() }.message shouldBe "Backend went away"
        }
    }

    @Test
    fun `a cancelled delete surfaces as cancellation, encoded or not`() {
        deleteStreams(DeleteOperationEvent.Error("Stopped by user", cancelled = true))
        runBlocking {
            shouldThrow<CancellationException> { collectDelete() }.message shouldContain "Stopped by user"
        }

        deleteStreams(DeleteOperationEvent.Error(IpcErrorCodec.encode(denial("delete")), cancelled = true))
        runBlocking { shouldThrow<CancellationException> { collectDelete() } }
    }

    @Test
    fun `a delete stream without a terminal event is reported as truncation`() {
        deleteStreams(DeleteOperationEvent.ScanProgress(1L, lookup(target)))

        runBlocking {
            shouldThrow<IOException> { collectDelete() }.message!! shouldContain "truncated"
        }
    }

    @Test
    fun `an encoded copy error surfaces as the host type`() {
        copyStreams(CopyOperationEvent.Error(IpcErrorCodec.encode(denial("copy")), cancelled = false))

        runBlocking {
            val thrown = shouldThrow<PathPermissionDeniedException> { collectCopy() }
            thrown.path!!.path shouldBe target.path
            thrown.operation shouldBe "copy"
        }
    }

    @Test
    fun `a markerless copy error surfaces as an IOException with the host message`() {
        copyStreams(CopyOperationEvent.Error("Backend went away", cancelled = false))

        runBlocking {
            shouldThrow<IOException> { collectCopy() }.message shouldBe "Backend went away"
        }
    }

    @Test
    fun `a cancelled copy surfaces as cancellation, encoded or not`() {
        copyStreams(CopyOperationEvent.Error("Stopped by user", cancelled = true))
        runBlocking {
            shouldThrow<CancellationException> { collectCopy() }.message shouldContain "Stopped by user"
        }

        copyStreams(CopyOperationEvent.Error(IpcErrorCodec.encode(denial("copy")), cancelled = true))
        runBlocking { shouldThrow<CancellationException> { collectCopy() } }
    }

    @Test
    fun `a copy stream without a terminal event is reported as truncation`() {
        copyStreams(CopyOperationEvent.ScanProgress(1L, 0L, lookup(target)))

        runBlocking {
            shouldThrow<IOException> { collectCopy() }.message!! shouldContain "truncated"
        }
    }

    @Test
    fun `an encoded move error surfaces as the host type`() {
        moveStreams(MoveOperationEvent.Error(IpcErrorCodec.encode(denial("move")), cancelled = false))

        runBlocking {
            val thrown = shouldThrow<PathPermissionDeniedException> { collectMove() }
            thrown.path!!.path shouldBe target.path
            thrown.operation shouldBe "move"
        }
    }

    @Test
    fun `a markerless move error surfaces as an IOException with the host message`() {
        moveStreams(MoveOperationEvent.Error("Backend went away", cancelled = false))

        runBlocking {
            shouldThrow<IOException> { collectMove() }.message shouldBe "Backend went away"
        }
    }

    @Test
    fun `a cancelled move surfaces as cancellation, encoded or not`() {
        moveStreams(MoveOperationEvent.Error("Stopped by user", cancelled = true))
        runBlocking {
            shouldThrow<CancellationException> { collectMove() }.message shouldContain "Stopped by user"
        }

        moveStreams(MoveOperationEvent.Error(IpcErrorCodec.encode(denial("move")), cancelled = true))
        runBlocking { shouldThrow<CancellationException> { collectMove() } }
    }

    @Test
    fun `a move stream without a terminal event is reported as truncation`() {
        moveStreams(MoveOperationEvent.ScanProgress(1L, 0L, lookup(target)))

        runBlocking {
            shouldThrow<IOException> { collectMove() }.message!! shouldContain "truncated"
        }
    }

    @Test
    fun `a host side delete failure crosses as its own type`() {
        val hostError = denial("delete").apply {
            stackTrace = arrayOf(StackTraceElement("com.host.Delete", "deleteStart", "Delete.kt", 42))
        }
        coEvery { hostOps.lookup(target, LookupOptions.BASE) } returns lookup(target)
        coEvery { hostOps.exists(target) } returns true
        coEvery { hostOps.delete(target, any()) } throws hostError
        every { connection.deleteStream(any(), any(), any(), any()) } answers {
            host().deleteStream(listOf(target), true, true, null)
        }

        runBlocking {
            val thrown = shouldThrow<PathPermissionDeniedException> { collectDelete() }
            thrown.path!!.path shouldBe target.path
            thrown.reason shouldBe PathPermissionDeniedException.Reason.NOT_PERMITTED
            thrown.stackTrace.any { it.className == "com.host.Delete" } shouldBe true
        }
    }

    @Test
    fun `a host side cancellation crosses as cancellation, not as a failure`() {
        coEvery { hostOps.lookup(target, LookupOptions.BASE) } returns lookup(target)
        coEvery { hostOps.exists(target) } returns true
        coEvery { hostOps.delete(target, any()) } throws CancellationException("Stopped by user")
        every { connection.deleteStream(any(), any(), any(), any()) } answers {
            host().deleteStream(listOf(target), true, true, null)
        }

        runBlocking {
            shouldThrow<CancellationException> { collectDelete() }.message shouldContain "Stopped by user"
        }
    }

    @Test
    fun `a host side copy failure crosses as its own type`() {
        val hostError = denial("copy").apply {
            stackTrace = arrayOf(StackTraceElement("com.host.Copy", "copyStart", "Copy.kt", 42))
        }
        coEvery { hostOps.lookup(destination, any()) } throws hostError
        every { connection.copyStream(any(), any(), any(), any(), any(), any()) } answers {
            host().copyStream(listOf(target), destination, false, false, false, null)
        }

        runBlocking {
            val thrown = shouldThrow<PathPermissionDeniedException> { collectCopy() }
            thrown.path!!.path shouldBe target.path
            thrown.operation shouldBe "copy"
            thrown.reason shouldBe PathPermissionDeniedException.Reason.NOT_PERMITTED
            thrown.stackTrace.any { it.className == "com.host.Copy" } shouldBe true
        }
    }

    @Test
    fun `a host side copy cancellation crosses as cancellation, not as a failure`() {
        coEvery { hostOps.lookup(destination, any()) } throws CancellationException("Stopped by user")
        every { connection.copyStream(any(), any(), any(), any(), any(), any()) } answers {
            host().copyStream(listOf(target), destination, false, false, false, null)
        }

        runBlocking {
            shouldThrow<CancellationException> { collectCopy() }.message shouldContain "Stopped by user"
        }
    }

    @Test
    fun `a host side move failure crosses as its own type`() {
        val hostError = denial("move").apply {
            stackTrace = arrayOf(StackTraceElement("com.host.Move", "moveStart", "Move.kt", 42))
        }
        coEvery { hostOps.lookup(destination, any()) } throws hostError
        every { connection.moveStream(any(), any(), any(), any(), any(), any()) } answers {
            host().moveStream(listOf(target), destination, false, false, false, null)
        }

        runBlocking {
            val thrown = shouldThrow<PathPermissionDeniedException> { collectMove() }
            thrown.path!!.path shouldBe target.path
            thrown.operation shouldBe "move"
            thrown.reason shouldBe PathPermissionDeniedException.Reason.NOT_PERMITTED
            thrown.stackTrace.any { it.className == "com.host.Move" } shouldBe true
        }
    }

    @Test
    fun `a host side move cancellation crosses as cancellation, not as a failure`() {
        coEvery { hostOps.lookup(destination, any()) } throws CancellationException("Stopped by user")
        every { connection.moveStream(any(), any(), any(), any(), any(), any()) } answers {
            host().moveStream(listOf(target), destination, false, false, false, null)
        }

        runBlocking {
            shouldThrow<CancellationException> { collectMove() }.message shouldContain "Stopped by user"
        }
    }
}
