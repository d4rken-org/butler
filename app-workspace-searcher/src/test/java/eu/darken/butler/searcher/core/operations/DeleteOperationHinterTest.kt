package eu.darken.butler.searcher.core.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.CoreDeleteExecutor
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Instant

class DeleteOperationHinterTest : BaseTest() {

    @Test
    fun `delete operation publishes removed paths to the hinter`() = runTest {
        val hinter = FileSystemHinter()
        val events = mutableListOf<FileSystemEvent>()
        hinter.events.onEach { events.add(it) }.launchIn(backgroundScope)
        runCurrent()

        val configSlot = slot<CoreDeleteExecutor.Config>()
        val executor = mockk<CoreDeleteExecutor> {
            coEvery { execute(any(), capture(configSlot)) } returns emptyFlow()
        }

        val target = LocalPath.build(File("/tmp/delete-test/file.txt"))
        val operation = DeleteOperation(
            workspaceId = Workspace.Id(),
            command = SearcherCommand.Delete(targets = setOf(target)),
            issueHandler = mockk(),
            coreDeleteExecutor = executor,
            fileSystemHinter = hinter,
        )
        val context = Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST)
        operation.perform(context).onEach { }.launchIn(backgroundScope)
        runCurrent()

        val removedLookup = LocalPathLookup(
            lookedUp = target,
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = Instant.DISTANT_PAST,
        )
        configSlot.captured.onPathsRemoved(setOf(removedLookup))
        runCurrent()

        val event = events.single().shouldBeInstanceOf<FileSystemEvent.Removed>()
        event.operationId shouldBe context.id
        event.paths.single().lookedUp shouldBe target
    }
}
