package eu.darken.butler.explorer.ui.explorer

import android.content.Context
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExplorerTrashControllerTest : BaseTest() {

    private fun trashRootItem(itemId: Uuid = Uuid.random()): ExplorerItem.Trash.Root = ExplorerItem.Trash.Root(
        itemId = itemId,
        deletedAt = Instant.fromEpochMilliseconds(0),
        originalLookup = mockk(),
        trashLookup = null,
    )

    private fun mockContext(): Context = mockk<Context>().apply {
        every { getString(any()) } returns "error message"
    }

    private fun mockWorkspace(): ExplorerWorkspace = mockk<ExplorerWorkspace>().apply {
        coEvery { navigate(any()) } just Runs
    }

    private fun CoroutineScope.controller(
        trashManager: TrashManager,
        trashRepo: TrashRepo,
        workspace: ExplorerWorkspace = mockWorkspace(),
        clearSelection: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) = ExplorerTrashController(
        context = mockContext(),
        trashManager = trashManager,
        trashRepo = trashRepo,
        workspace = { workspace },
        clearSelection = clearSelection,
        onError = onError,
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `successful restore refreshes and clears selection`() = runTest {
        var selectionCleared = false
        val trashRepo = mockk<TrashRepo>().apply {
            coEvery { getById(any()) } returns mockk()
        }
        val trashManager = mockk<TrashManager>().apply {
            coEvery { restore(any()) } returns mockk {
                every { restored } returns setOf(LocalPath.build("/tmp/trash-test/restored"))
                every { failed } returns emptySet()
                every { conflicts } returns emptySet()
            }
        }
        val workspace = mockWorkspace()
        val controller = controller(
            trashManager = trashManager,
            trashRepo = trashRepo,
            workspace = workspace,
            clearSelection = { selectionCleared = true },
        )

        controller.restoreRoot(listOf(trashRootItem()))
        runCurrent()

        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }
        selectionCleared shouldBe true
    }

    @Test
    fun `restore with no repo entries surfaces an error`() = runTest {
        var error: Throwable? = null
        var selectionCleared = false
        val trashRepo = mockk<TrashRepo>().apply {
            coEvery { getById(any()) } returns null
        }
        val trashManager = mockk<TrashManager>()
        val workspace = mockWorkspace()
        val controller = controller(
            trashManager = trashManager,
            trashRepo = trashRepo,
            workspace = workspace,
            clearSelection = { selectionCleared = true },
            onError = { error = it },
        )

        controller.restoreRoot(listOf(trashRootItem()))
        runCurrent()

        error shouldNotBe null
        selectionCleared shouldBe true
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `failed permanent delete surfaces an error without refresh`() = runTest {
        var error: Throwable? = null
        val trashRepo = mockk<TrashRepo>().apply {
            coEvery { getById(any()) } returns mockk()
        }
        val trashManager = mockk<TrashManager>().apply {
            coEvery { deletePermanently(any()) } returns 0
        }
        val workspace = mockWorkspace()
        val controller = controller(
            trashManager = trashManager,
            trashRepo = trashRepo,
            workspace = workspace,
            onError = { error = it },
        )

        controller.deleteRootPermanently(listOf(trashRootItem()))
        runCurrent()

        error shouldNotBe null
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `empty trash refreshes on success and surfaces failures`() = runTest {
        val workspace = mockWorkspace()
        val okManager = mockk<TrashManager>().apply {
            coEvery { emptyTrash() } returns 3
        }
        controller(trashManager = okManager, trashRepo = mockk(), workspace = workspace).emptyTrash()
        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }

        var error: Throwable? = null
        val failingManager = mockk<TrashManager>().apply {
            coEvery { emptyTrash() } throws IllegalStateException("boom")
        }
        controller(trashManager = failingManager, trashRepo = mockk(), onError = { error = it }).emptyTrash()
        error?.message shouldBe "boom"
    }
}
