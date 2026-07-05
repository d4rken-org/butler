package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import testhelpers.BaseTest

class DocumentBufferCancellationTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    @Test
    fun `initialize completes normally`() = runTest {
        val content = "x".repeat(500)
        val blockSize = 100

        val dataSource = InMemoryDataSource(workspaceId, content)
        dataSource.open()

        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = blockSize,
            assertions = true,
        )

        val result = buffer.initialize()
        result.isSuccess shouldBe true
    }

    @Test
    fun `initialize respects coroutine cancellation`() = runTest {
        val content = "Line\n".repeat(1000)
        val blockSize = 100

        val dataSource = InMemoryDataSource(workspaceId, content)
        dataSource.open()

        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = blockSize,
            assertions = true,
        )

        val job = async {
            buffer.initialize()
        }

        // Cancel immediately — ensureActive() should catch this
        job.cancel()

        // await() on a cancelled deferred throws CancellationException
        assertThrows<CancellationException> { job.await() }

        // Buffer should not be fully initialized
        buffer.totalLines.value shouldBe 0L
    }
}
