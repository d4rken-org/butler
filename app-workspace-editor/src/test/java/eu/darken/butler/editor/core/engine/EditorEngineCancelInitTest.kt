package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import kotlin.random.Random
import testhelpers.coroutine.TestDispatcherProvider

class EditorEngineCancelInitTest : EditorEngineTestBase() {

    @Test
    fun `cancelInitialization aborts a hanging load and leaves the engine empty`() = runTest {
        // The load hangs on the very first gateway access, so the cancel deterministically
        // lands mid-initialization. That access is the lookup: open() attempts the real read and
        // only probes existence afterwards, to tell a deleted file apart from an unreadable one.
        val gateway = mockk<GatewaySwitch>().apply {
            coEvery { lookup(any(), any()) } coAnswers { awaitCancellation() }
        }
        val fileFactory = object : FileDataSource.Factory {
            override fun create(
                workspaceId: Workspace.Id,
                filePath: eu.darken.butler.common.files.APath<*>,
                gatewaySwitch: GatewaySwitch,
                charsetOverride: Charset?,
            ) = FileDataSource(workspaceId, filePath, gatewaySwitch, charsetOverride)
        }
        val documentBufferFactory = object : DocumentBuffer.Factory {
            override fun create(
                workspaceId: Workspace.Id,
                dataSource: EditorDataSource,
                maxUndoStackSize: Int,
                maxUndoMemoryBytes: Long,
                blockSize: Int,
                assertions: Boolean,
                staleSampleRandom: Random,
                timeSource: kotlin.time.TimeSource,
                maxDisplayLineChars: Int,
            ) = DocumentBuffer(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, blockSize, true, staleSampleRandom)
        }
        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = LocalPath.build("/test/hanging.txt"),
            initialContent = null,
            gatewaySwitch = gateway,
            editorSettings = createMockSettings(),
            dispatcherProvider = TestDispatcherProvider(),
            fileDataSourceFactory = fileFactory,
            inMemoryDataSourceFactory = mockk(),
            documentBufferFactory = documentBufferFactory,
        )

        // cancelInitialization cancels the JOB RUNNING initialize() (that is its contract), so
        // the launched coroutine ends up cancelled; initialize() itself still returns the
        // failure Result first - capture it via assignment, not await (await would throw)
        var result: Result<Unit>? = null
        val initJob = launch { result = engine.initialize() }
        runCurrent()
        engine.state.first { it is EditorState.Loading }

        engine.cancelInitialization()
        initJob.join()

        result!!.isFailure.shouldBeTrue()
        result!!.exceptionOrNull().shouldBeInstanceOf<CancellationException>()
        engine.state.value shouldBe EditorState.Empty
        engine.progress.value shouldBe null
    }
}
