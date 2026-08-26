package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.workspaces.classic.ClassicWorkspaceContainer
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

private fun pageTag(id: Workspace.Id) = "page-${id.longTag}"

/**
 * Back dispatch while the classic pager rests on its trailing new-tab placeholder page.
 *
 * Regression cover for back closing the previously active tab instead of returning to it: focus
 * legitimately stays on the last tab while the pager is parked on the placeholder, so that tab's
 * own back handler — Explorer's back-at-root closes its workspace — was still armed for a page the
 * user is not looking at. What prevents it is the container handing each pane a `backActive` signal
 * derived from where the pager is actually resting, plus a container-level handler that scrolls
 * back to the focused tab.
 *
 * The real container, panes, layers and [WorkspaceBackHandler] are composed; only the page behind
 * the host is a stand-in, since the real ones instantiate Hilt ViewModels. Its back handler closes
 * the tab, which is exactly the Explorer behaviour this bug turns on.
 *
 * Known gap: the container owns its `PagerState` and exposes nothing, so the mid-scroll half of the
 * gate cannot be observed from here — a held drag applies no scroll offset under Robolectric, and
 * inferring "the pager is moving" from layout alone is not sound. That half is pinned directly on
 * the predicate in
 * `eu.darken.butler.workspace.ui.workspaces.classic.PagerFocusCoordinatorTest`, whose harness owns
 * the pager.
 */
class ClassicPlaceholderBackDispatchTest : ComposeTest() {

    private val idA = Workspace.Id()
    private val idB = Workspace.Id()

    private fun info(id: Workspace.Id) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private val placeholderTitle: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.workspace_ondemand_swipe_title)

    /** Stands in for a page whose top-level back consumes the press and closes its tab — the worst case for the dispatch order under test. */
    private class ClosingPageHost(private val onCloseTab: (Workspace.Id) -> Unit) : WorkspacePageHostEntry {

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            WorkspaceBackHandler(enabled = true) { onCloseTab(id) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(pageTag(id)),
            )
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        }
    }

    private class Outcome {
        val closedTabs = mutableListOf<Workspace.Id>()
        val screenActions = mutableListOf<WorkspaceScreenAction>()
        var overlayDismissed = false
        var reachedAppRoot = false
        var dispatcher: OnBackPressedDispatcher? = null
    }

    private fun Outcome.pressBack() {
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()
    }

    private fun compose(
        focused: Workspace.Id? = idB,
        overlayVisible: Boolean = false,
        managerDialogs: List<ManagerDialog> = emptyList(),
    ): Outcome {
        val outcome = Outcome()

        val pageHosts = mapOf<Workspace.Type, WorkspacePageHostEntry>(
            Workspace.Type.EXPLORER to ClosingPageHost { outcome.closedTabs.add(it) },
        )

        val state = WorkspacesViewModel.State(
            state = WorkspaceRemote.State(infos = listOf(info(idA), info(idB))),
            focusedWorkspace = focused,
            selectedWorkspaces = focused?.let { mapOf(0 to it) } ?: emptyMap(),
            isUpgraded = true,
            // Both are required for the trailing placeholder page to exist at all.
            swipeGesturesEnabled = true,
            onDemandWorkspaceCreation = true,
        )

        composeTestRule.setContent {
            outcome.dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspacePageHosts provides pageHosts) {
                    // Stands in for MainActivity's press-back-again-to-exit prompt: registered
                    // above everything else, so it only runs once nothing in the workspace tree
                    // consumed the press. A raw handler on purpose — that is what the real one is,
                    // and its LIFO position is the whole point.
                    BackHandler(enabled = true) { outcome.reachedAppRoot = true }

                    // Registered where WorkspacesScreenHost registers it: above the workspace
                    // content, so it loses every LIFO race it takes part in.
                    ManagerOverlayBackHandler(
                        isOverlayVisible = overlayVisible,
                        onDismiss = { outcome.overlayDismissed = true },
                    )

                    ClassicWorkspaceContainer(
                        state = state,
                        managerDialogs = managerDialogs,
                        isOverlayVisible = overlayVisible,
                        // Recorded but not applied: focus must still never follow the swipe,
                        // or the desync these tests are built on would repair itself.
                        onWorkspaceScreenAction = { outcome.screenActions.add(it) },
                        managerDialogStates = emptyMap(),
                        onDismissManagerDialog = {},
                        onConfirmManagerDialog = {},
                        bannerStates = emptyMap(),
                        onDismissBanner = {},
                        onShareError = { _, _ -> },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // The focus sync parks the pager on the focused tab before anything else happens.
        if (focused != null) composeTestRule.onNodeWithTag(pageTag(focused)).assertIsDisplayed()

        return outcome
    }

    /**
     * Swipes onto the trailing placeholder and settles there.
     *
     * The closing `assertIsNotDisplayed` does more than check the pager moved: it throws on a node
     * that no longer exists, so it also proves the focused tab's page — and with it the back
     * handler this test is about — is still composed off screen. Were it disposed instead, every
     * "back does not close the tab" assertion below would pass for the wrong reason.
     */
    private fun swipeToPlaceholder() {
        composeTestRule.onNodeWithTag(pageTag(idB)).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(placeholderTitle, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(pageTag(idB)).assertIsNotDisplayed()
    }

    @Test
    fun `back on the placeholder does not close the focused tab`() {
        val outcome = compose()
        swipeToPlaceholder()

        outcome.pressBack()

        outcome.closedTabs shouldBe emptyList()
    }

    @Test
    fun `back on the placeholder returns to the focused tab`() {
        val outcome = compose()
        swipeToPlaceholder()

        outcome.pressBack()
        composeTestRule.onNodeWithTag(pageTag(idB)).assertIsDisplayed()

        // Back dispatch is with the pane again: the second press reaches the page, which is what
        // proves the first one moved the pager instead of merely being swallowed.
        outcome.pressBack()

        outcome.closedTabs shouldBe listOf(idB)
    }

    @Test
    fun `back on a real page still reaches the page and does not move the pager`() {
        val outcome = compose()

        outcome.pressBack()

        outcome.closedTabs shouldBe listOf(idB)
        composeTestRule.onNodeWithTag(pageTag(idB)).assertIsDisplayed()
    }

    /**
     * A [ManagerDialog.WorkspaceTargeted] dialog renders inside its pane, which is off screen while
     * the pager sits on the placeholder — so its own back handler is already disarmed. If the
     * container's handler deferred to it as well, nothing would handle back at all.
     */
    @Test
    fun `a workspace-targeted dialog does not silence back on the placeholder`() {
        val outcome = compose(
            managerDialogs = listOf(
                ManagerDialog.WorkspaceTargeted.CloseConfirmation(
                    id = "close-confirmation",
                    targetWorkspaceId = idB,
                    closingWorkspaceId = idB,
                    workspaceTitle = "Explorer".toCaString(),
                ),
            ),
        )
        swipeToPlaceholder()

        outcome.pressBack()

        outcome.closedTabs shouldBe emptyList()
        composeTestRule.onNodeWithTag(pageTag(idB)).assertIsDisplayed()
    }

    /**
     * The window between a swipe settling and the ViewModel's focus catching up with it: the pane
     * the pager rests on is not the focused one, and the focused one is not the page the pager
     * rests on — so neither page may take back. Nothing else must take it either, or the press
     * lands on the app-root exit prompt and a second one closes the app.
     *
     * The state is reached without any timing dependency: the harness swallows the container's
     * `Select` action, so focus simply never follows the swipe.
     */
    @Test
    fun `back is consumed while focus has not caught up with the pager`() {
        val outcome = compose()

        composeTestRule.onNodeWithTag(pageTag(idB)).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(pageTag(idA)).assertIsDisplayed()

        outcome.pressBack()

        outcome.closedTabs shouldBe emptyList()
        outcome.reachedAppRoot shouldBe false
        // Nothing moved either: the container swallows the press rather than acting on it.
        composeTestRule.onNodeWithTag(pageTag(idA)).assertIsDisplayed()
    }

    /**
     * The repair for the state above. Consuming the press is only acceptable because it also ends
     * the disagreement: focus adopts the page the pager rests on, so the next press reaches that
     * page instead of being consumed again. Without this the container would swallow every press
     * for as long as focus stayed behind.
     *
     * The pager deliberately does not move. The user swiped to this page; back must not undo that.
     */
    @Test
    fun `back at rest hands focus to the page the pager rests on`() {
        val outcome = compose()

        composeTestRule.onNodeWithTag(pageTag(idB)).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(pageTag(idA)).assertIsDisplayed()
        // The settle itself reports a selection; only what the press adds is under test.
        outcome.screenActions.clear()

        outcome.pressBack()

        outcome.screenActions shouldBe listOf(WorkspaceScreenAction.Select(idA))
        composeTestRule.onNodeWithTag(pageTag(idA)).assertIsDisplayed()
    }

    /**
     * The mirror image, without which every `reachedAppRoot shouldBe false` above would also hold
     * for a probe that can never run at all.
     */
    @Test
    fun `back reaches the app root when no workspace is focused`() {
        val outcome = compose(focused = null)

        outcome.pressBack()

        outcome.reachedAppRoot shouldBe true
        outcome.closedTabs shouldBe emptyList()
    }

    @Test
    fun `the manager overlay outranks both handlers on the placeholder`() {
        val outcome = compose(overlayVisible = true)
        swipeToPlaceholder()

        outcome.pressBack()

        outcome.overlayDismissed shouldBe true
        outcome.closedTabs shouldBe emptyList()
        // The container's handler stayed out of it too, so the pager did not move.
        composeTestRule.onNodeWithTag(pageTag(idB)).assertIsNotDisplayed()
    }
}
