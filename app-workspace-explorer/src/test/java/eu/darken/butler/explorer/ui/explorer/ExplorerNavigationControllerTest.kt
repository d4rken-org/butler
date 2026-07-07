package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File

class ExplorerNavigationControllerTest : BaseTest() {

    private val workspaceId = eu.darken.butler.workspace.core.Workspace.Id()

    private fun path(name: String) = LocalPath.build(File("/tmp/nav-test", name))

    private fun directoryItem(name: String): ExplorerItem.RegularDirectory {
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns path(name)
        }
        return ExplorerItem.RegularDirectory(lookup = lookup)
    }

    private fun fileItem(name: String): ExplorerItem.RegularFile {
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns path(name)
            every { this@apply.name } returns name
        }
        return ExplorerItem.RegularFile(lookup = lookup, mimeType = MimeInfo("text/plain"))
    }

    private fun mockWorkspace(): ExplorerWorkspace = mockk<ExplorerWorkspace>().apply {
        coEvery { navigate(any()) } just Runs
        every { pickerConfig } returns null
    }

    private fun dialogs() = ExplorerDialogController(
        filterState = { mockk() },
        useRegexPatterns = { false },
        clearSelection = {},
        tag = "test",
    )

    private fun CoroutineScope.controller(
        workspace: ExplorerWorkspace = mockWorkspace(),
        dialogs: ExplorerDialogController = dialogs(),
        favoritesRepo: ExplorerFavoritesRepo = mockk<ExplorerFavoritesRepo>().apply {
            coEvery { refresh() } just Runs
        },
        selectedItems: () -> Set<ExplorerItem> = { emptySet() },
        clearSelection: () -> Unit = {},
        state: ExplorerWorkspaceViewModel.State = ExplorerWorkspaceViewModel.State(),
    ) = ExplorerNavigationController(
        workspaceId = workspaceId,
        workspace = { workspace },
        workspaceRemote = mockk(),
        gatewaySwitch = mockk(),
        dialogs = dialogs,
        favoritesRepo = favoritesRepo,
        selectedItems = selectedItems,
        toggleSelection = {},
        clearSelection = clearSelection,
        getState = { state },
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `directory tap navigates and clears selection`() = runTest {
        var selectionCleared = false
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace, clearSelection = { selectionCleared = true })
        val item = directoryItem("docs")

        val expectedTarget = ExplorerNavigation.Target.Directory(item.lookup.lookedUp)
        controller.navigate(item as ExplorerItem)
        runCurrent()

        coVerify { workspace.navigate(expectedTarget) }
        selectionCleared shouldBe true
    }

    @Test
    fun `file tap without picker shows the file options dialog`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs)
        val item = fileItem("report.pdf")

        controller.navigate(item as ExplorerItem)
        runCurrent()

        val dialog = dialogs.current().shouldBeInstanceOf<ExplorerDialogState.FileOptions>()
        dialog.item shouldBe item
    }

    @Test
    fun `refresh re-resolves favorites BEFORE re-navigating`() = runTest {
        // This is the ordering contract from the checkpoint list: favorites must be
        // refreshed first so re-granted/remounted locations resolve in the new listing.
        val workspace = mockWorkspace()
        val favoritesRepo = mockk<ExplorerFavoritesRepo>().apply {
            coEvery { refresh() } just Runs
        }
        val controller = controller(workspace = workspace, favoritesRepo = favoritesRepo)

        controller.retryNavigation()
        runCurrent()

        coVerifyOrder {
            favoritesRepo.refresh()
            workspace.navigate(ExplorerNavigation.Refresh)
        }
    }

    @Test
    fun `reveal emits a request and highlights the items`() = runTest {
        val controller = controller()
        val received = mutableListOf<ExplorerWorkspaceViewModel.RevealRequest>()
        val collector = launch { controller.revealRequests.collect { received.add(it) } }
        runCurrent()

        val target = path("show-me.txt")
        controller.revealItems(listOf(target))
        runCurrent()

        received.map { it.path.path } shouldBe listOf(target.path)
        received.first().highlight shouldBe true
        controller.highlightedItemIds.value shouldBe setOf(target.path)

        controller.clearHighlights()
        controller.highlightedItemIds.value shouldBe emptySet()

        collector.cancel()
    }

    @Test
    fun `going back reveals the previous directory without highlighting`() = runTest {
        val current = path("current-dir")
        val location = mockk<ExplorerLocation.Directory>().apply {
            every { this@apply.path } returns current
            every { progress } returns null
            every { info } returns null
        }
        val workspace = mockWorkspace()
        val controller = controller(
            workspace = workspace,
            state = ExplorerWorkspaceViewModel.State(currentLocation = location),
        )
        val received = mutableListOf<ExplorerWorkspaceViewModel.RevealRequest>()
        val collector = launch { controller.revealRequests.collect { received.add(it) } }
        runCurrent()

        controller.goBack()
        runCurrent()

        coVerify { workspace.navigate(ExplorerNavigation.Back) }
        received.single().highlight shouldBe false
        controller.highlightedItemIds.value shouldBe emptySet()

        collector.cancel()
    }

    @Test
    fun `dismissing a navigation error goes back when possible otherwise home`() = runTest {
        val backWorkspace = mockWorkspace()
        controller(workspace = backWorkspace, state = ExplorerWorkspaceViewModel.State(canGoBack = true))
            .dismissNavigationError()
        runCurrent()
        coVerify { backWorkspace.navigate(ExplorerNavigation.Back) }

        val homeWorkspace = mockWorkspace()
        controller(workspace = homeWorkspace, state = ExplorerWorkspaceViewModel.State(canGoBack = false))
            .dismissNavigationError()
        runCurrent()
        coVerify { homeWorkspace.navigate(ExplorerNavigation.Target.Home) }
    }

    @Test
    fun `edited local path navigates to the rebuilt absolute path`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(LocalPath.build("/storage/emulated/0"), "storage/Documents ")
        runCurrent()

        coVerify {
            workspace.navigate(
                ExplorerNavigation.Target.Directory(LocalPath.build("/storage/Documents")),
            )
        }
    }
}
