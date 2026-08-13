package eu.darken.butler.apps.ui.apps

import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * A drag produces selection ranges far faster than the round trip through the workspace. They have
 * to be applied in order, or an expanding range that was overtaken lands after the retreat that
 * followed it and re-selects what the user just gave back.
 */
class AppsDragSelectionOrderTest : BaseTest() {

    private val apps = listOf(
        AppsMockDataProvider.Presets.chromeItem,
        AppsMockDataProvider.Presets.settingsItem,
        AppsMockDataProvider.Presets.notesItem,
        AppsMockDataProvider.Presets.disabledAppItem,
    )
    private val ids = apps.map { it.pkg.installId }

    @Test
    fun `a burst of ranges ends on the last one, never on an overtaken larger range`() = runTest {
        val applied = mutableListOf<Set<InstallId>>()
        // Each token lets exactly one in-flight update complete, so the burst arrives while the
        // workspace is still busy with the first one.
        val completions = Channel<Unit>(Channel.UNLIMITED)
        val workspace = mockk<AppsWorkspace>(relaxed = true)
        coEvery { workspace.setSelection(any()) } coAnswers {
            val requested = firstArg<Set<InstallId>>()
            completions.receive()
            applied += requested
        }
        val vm = createVM(workspace)

        val first = setOf(ids[0], ids[1])
        val expanded = setOf(ids[0], ids[1], ids[2], ids[3])
        val retreated = setOf(ids[0])
        vm.onPageAction(AppsPageAction.Selection.SetSelection(first))
        vm.onPageAction(AppsPageAction.Selection.SetSelection(expanded))
        vm.onPageAction(AppsPageAction.Selection.SetSelection(retreated))

        completions.send(Unit)
        completions.send(Unit)

        // The overtaken middle range may be dropped, the last one never is.
        applied shouldBe listOf(first, retreated)
    }

    @Test
    fun `a clear that follows a range is never overtaken by it`() = runTest {
        val applied = mutableListOf<String>()
        val completions = Channel<Unit>(Channel.UNLIMITED)
        val workspace = mockk<AppsWorkspace>(relaxed = true)
        coEvery { workspace.setSelection(any()) } coAnswers {
            val requested = firstArg<Set<InstallId>>()
            completions.receive()
            applied += "set(${requested.size})"
        }
        coEvery { workspace.clearSelection() } coAnswers {
            completions.receive()
            applied += "clear"
        }
        val vm = createVM(workspace)

        // The second range is still queued when the clear arrives - it must not land after it and
        // bring the selection the user just gave back.
        vm.onPageAction(AppsPageAction.Selection.SetSelection(setOf(ids[0], ids[1])))
        vm.onPageAction(AppsPageAction.Selection.SetSelection(ids.toSet()))
        vm.onPageAction(AppsPageAction.Selection.Clear)

        repeat(3) { completions.send(Unit) }

        applied shouldBe listOf("set(2)", "set(4)", "clear")
    }

    @Test
    fun `a tap queued behind a burst of ranges lands after them`() = runTest {
        val applied = mutableListOf<String>()
        val completions = Channel<Unit>(Channel.UNLIMITED)
        val workspace = mockk<AppsWorkspace>(relaxed = true)
        // A populated Ready state puts the click into multi-select mode so it routes to a toggle.
        every { workspace.state } returns flowOf(
            AppsWorkspace.State.Ready(
                apps = apps,
                filteredApps = apps,
                selectedAppIds = setOf(ids[0]),
            )
        )
        coEvery { workspace.setSelection(any()) } coAnswers {
            val requested = firstArg<Set<InstallId>>()
            completions.receive()
            applied += "set(${requested.size})"
        }
        // The toggle resolves atomically against the engine's selection, so it takes no boolean.
        coEvery { workspace.toggleSelection(any()) } coAnswers {
            completions.receive()
            applied += "toggle(${firstArg<InstallId>()})"
        }
        val vm = createVM(workspace)

        vm.onPageAction(AppsPageAction.Selection.SetSelection(setOf(ids[0], ids[1])))
        vm.onPageAction(AppsPageAction.Selection.SetSelection(setOf(ids[0], ids[1], ids[2])))
        vm.onPageAction(AppsPageAction.Selection.SetSelection(ids.toSet()))
        // A tap while the selection is active toggles - it must not be discarded by the burst.
        vm.onPageAction(AppsPageAction.Apps.Click(apps[1]))

        repeat(3) { completions.send(Unit) }

        applied shouldBe listOf("set(2)", "set(4)", "toggle(${ids[1]})")
    }

    private fun createVM(workspace: AppsWorkspace): AppsWorkspaceViewModel {
        val id = Workspace.Id()
        return AppsWorkspaceViewModel(
            id = id,
            context = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider(),
            workspaceProvider = mockk<WorkspaceProvider> { every { retrieve(id) } returns flowOf(workspace) },
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true),
            appsSettings = mockk(relaxed = true),
            appSizeCache = mockk(relaxed = true),
        )
    }
}
