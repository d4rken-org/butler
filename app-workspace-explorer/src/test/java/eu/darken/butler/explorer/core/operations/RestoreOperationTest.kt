package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RestoreOperationTest : BaseTest() {

    private val restoredPath = LocalPath.build(File("/tmp/restore-test/pair"))

    private fun operation(
        trashRepo: TrashRepo,
        trashManager: TrashManager,
        hinter: FileSystemHinter = FileSystemHinter(),
        command: ExplorerCommand.Restore,
    ): RestoreOperation {
        @Suppress("UNCHECKED_CAST")
        val restoredLookup = LocalPathLookup(
            lookedUp = restoredPath,
            fileType = FileType.DIRECTORY,
            size = null,
            modifiedAt = Instant.DISTANT_PAST,
        ) as APathLookup<APath<*>>
        val gatewaySwitch = mockk<GatewaySwitch> {
            coEvery { lookup(any(), any()) } returns restoredLookup
        }
        return RestoreOperation(
            workspaceId = Workspace.Id(),
            command = command,
            trashRepo = trashRepo,
            trashManager = trashManager,
            gatewaySwitch = gatewaySwitch,
            fileSystemHinter = hinter,
        )
    }

    @Test
    fun `successful root restore reports added paths and publishes hinter event`(): Unit = runTest {
        val hinter = FileSystemHinter()
        val events = mutableListOf<FileSystemEvent>()
        hinter.events.onEach { events.add(it) }.launchIn(backgroundScope)
        runCurrent()

        val itemId = Uuid.random()
        val trashRepo = mockk<TrashRepo> {
            coEvery { getById(itemId) } returns mockk()
        }
        val trashManager = mockk<TrashManager> {
            coEvery { restore(any()) } returns TrashManager.TrashRestoreReport(
                restored = setOf(restoredPath),
                failed = emptySet(),
                conflicts = emptySet(),
            )
        }
        val op = operation(
            trashRepo = trashRepo,
            trashManager = trashManager,
            hinter = hinter,
            command = ExplorerCommand.Restore(rootItemIds = setOf(itemId), restoredPaths = listOf(restoredPath)),
        )

        val completed = op.perform(Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST))
            .last() as ExplorerOperation.State.Completed
        runCurrent()

        completed.error shouldBe null
        val report = completed.report.shouldBeInstanceOf<RestoreOperation.Report>()
        report.restoredPaths shouldBe setOf(restoredPath)
        report.affectedPaths.single().change shouldBe Operation.Report.PathChange.Change.ADDED
        events.single().shouldBeInstanceOf<FileSystemEvent.Added>().paths.single().lookedUp shouldBe restoredPath
    }

    @Test
    fun `full failure completes with error and failure counts`(): Unit = runTest {
        val itemId = Uuid.random()
        val trashRepo = mockk<TrashRepo> {
            coEvery { getById(itemId) } returns mockk()
        }
        val trashManager = mockk<TrashManager> {
            coEvery { restore(any()) } throws IllegalStateException("disk broke")
        }
        val op = operation(
            trashRepo = trashRepo,
            trashManager = trashManager,
            command = ExplorerCommand.Restore(rootItemIds = setOf(itemId), restoredPaths = listOf(restoredPath)),
        )

        val completed = op.perform(Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST))
            .last() as ExplorerOperation.State.Completed

        (completed.error != null) shouldBe true
        val report = completed.report.shouldBeInstanceOf<RestoreOperation.Report>()
        report.restoredPaths shouldBe emptySet()
        report.failedCount shouldBe 1
    }

    @Test
    fun `missing parent for nested restore counts targets as failed`(): Unit = runTest {
        val parentId = Uuid.random()
        val trashRepo = mockk<TrashRepo> {
            coEvery { getById(parentId) } returns null
        }
        val op = operation(
            trashRepo = trashRepo,
            trashManager = mockk(),
            command = ExplorerCommand.Restore(
                nestedItems = listOf(
                    ExplorerCommand.Restore.NestedTarget(parentId = parentId, relativePath = "a.txt"),
                    ExplorerCommand.Restore.NestedTarget(parentId = parentId, relativePath = "b.txt"),
                ),
                restoredPaths = listOf(restoredPath),
            ),
        )

        val completed = op.perform(Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST))
            .last() as ExplorerOperation.State.Completed

        val report = completed.report.shouldBeInstanceOf<RestoreOperation.Report>()
        report.failedCount shouldBe 2
        report.restoredPaths shouldBe emptySet()
    }

    @Test
    fun `a mixed restore is about a root item, not a nested one`(): Unit = runTest {
        val rootPath = LocalPath.build(File("/tmp/restore-test/root-folder"))
        val nestedPath = LocalPath.build(File("/tmp/restore-test/parent/nested.txt"))
        val rootId = Uuid.random()
        val parentId = Uuid.random()
        val parentItem = mockk<TrashRepo.TrashItem>()
        val trashRepo = mockk<TrashRepo> {
            coEvery { getById(rootId) } returns mockk()
            coEvery { getById(parentId) } returns parentItem
        }
        val trashManager = mockk<TrashManager> {
            coEvery { restore(any()) } returns TrashManager.TrashRestoreReport(
                restored = setOf(rootPath),
                failed = emptySet(),
                conflicts = emptySet(),
            )
            coEvery { restoreNested(parentItem, "nested.txt") } returns TrashManager.TrashRestoreReport(
                restored = setOf(nestedPath),
                failed = emptySet(),
                conflicts = emptySet(),
            )
        }
        val op = operation(
            trashRepo = trashRepo,
            trashManager = trashManager,
            command = ExplorerCommand.Restore(
                rootItemIds = setOf(rootId),
                nestedItems = listOf(
                    ExplorerCommand.Restore.NestedTarget(parentId = parentId, relativePath = "nested.txt"),
                ),
                restoredPaths = listOf(rootPath, nestedPath),
            ),
        )

        val completed = op.perform(Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST))
            .last() as ExplorerOperation.State.Completed

        val report = completed.report.shouldBeInstanceOf<RestoreOperation.Report>()
        report.restoredPaths shouldBe setOf(rootPath, nestedPath)
        // A nested restore names a file the user reached through a parent, so a root item wins.
        report.subjectPath shouldBe rootPath
    }

    @Test
    fun `a nested-only restore names the nested path`(): Unit = runTest {
        val nestedPath = LocalPath.build(File("/tmp/restore-test/parent/nested.txt"))
        val parentId = Uuid.random()
        val parentItem = mockk<TrashRepo.TrashItem>()
        val trashRepo = mockk<TrashRepo> {
            coEvery { getById(parentId) } returns parentItem
        }
        val trashManager = mockk<TrashManager> {
            coEvery { restoreNested(parentItem, "nested.txt") } returns TrashManager.TrashRestoreReport(
                restored = setOf(nestedPath),
                failed = emptySet(),
                conflicts = emptySet(),
            )
        }
        val op = operation(
            trashRepo = trashRepo,
            trashManager = trashManager,
            command = ExplorerCommand.Restore(
                nestedItems = listOf(
                    ExplorerCommand.Restore.NestedTarget(parentId = parentId, relativePath = "nested.txt"),
                ),
                restoredPaths = listOf(nestedPath),
            ),
        )

        val completed = op.perform(Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST))
            .last() as ExplorerOperation.State.Completed

        completed.report.shouldBeInstanceOf<RestoreOperation.Report>().subjectPath shouldBe nestedPath
    }

    @Test
    fun `the path plan targets the paths being restored`() {
        val plan = operation(
            trashRepo = mockk(),
            trashManager = mockk(),
            command = ExplorerCommand.Restore(
                rootItemIds = setOf(Uuid.random()),
                restoredPaths = listOf(restoredPath),
            ),
        ).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(restoredPath)
        plan.destination shouldBe null
        plan.scopePaths shouldContainExactly listOf(restoredPath)
    }
}
