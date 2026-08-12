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

    private val ids = listOf(
        AppsMockDataProvider.Presets.chromeItem,
        AppsMockDataProvider.Presets.settingsItem,
        AppsMockDataProvider.Presets.notesItem,
        AppsMockDataProvider.Presets.disabledAppItem,
    ).map { it.pkg.installId }

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
