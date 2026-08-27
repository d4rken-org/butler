package eu.darken.butler.workspace.ui.workspaces.classic

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.LocalWorkspacePagerVisibility
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import eu.darken.butler.workspace.ui.workspaces.WorkspaceScreenAction
import eu.darken.butler.workspace.ui.workspaces.WorkspacesViewModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The classic pager against a real [androidx.compose.foundation.pager.HorizontalPager].
 *
 * Once a pane-local child stacks inside its tab's page instead of covering the screen, focus can sit
 * on a workspace that owns no page at all — the pager is keyed by tab. Everything here turns on the
 * container resolving one owning-tab id and driving the pager, pane focus and the visibility
 * publisher from it.
 *
 * On-demand creation is off in every fixture: the trailing placeholder animates forever, so
 * composing it would stall the Robolectric clock. Its own rules are covered by
 * [PlaceholderCreationScopeTest] and [PlaceholderCreationControllerTest].
 */
class ClassicWorkspacePagerTest : ComposeTest() {

    private val tabA = Workspace.Id()
    private val tabB = Workspace.Id()
    private val child = Workspace.Id()

    private val placeholderTitle: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.workspace_ondemand_swipe_title)

    private fun tab(id: Workspace.Id) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Tab ${id.shortTag}".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private fun paneLocalChild(id: Workspace.Id, caller: Workspace.Id) = Workspace.Info(
        id = id,
        type = Workspace.Type.APP_DETAILS,
        title = "Child ${id.shortTag}".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
        callerWorkspaceId = caller,
        modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
        pausableAsChild = true,
    )

    private fun state(
        infos: List<Workspace.Info>,
        focused: Workspace.Id?,
        selected: Map<Int, Workspace.Id>,
        onDemandCreation: Boolean = false,
    ) = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(infos = infos),
        focusedWorkspace = focused,
        selectedWorkspaces = selected,
        visiblePaneSelections = WorkspacePageManager.State(
            selectedWorkspaces = selected,
            currentPaneCount = 1,
        ).visiblePaneAssignments,
        isUpgraded = true,
        swipeGesturesEnabled = true,
        onDemandWorkspaceCreation = onDemandCreation,
        currentPaneCount = 1,
    )

    /** Tags every page it renders and reports what it was told about its own focus. */
    private class RecordingHost : WorkspacePageHostEntry {
        val focused = mutableMapOf<Workspace.Id, Boolean>()

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            focused[id] = LocalWorkspaceFocused.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(tagFor(id)),
            )
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit

        companion object {
            fun tagFor(id: Workspace.Id) = "page-${id.shortTag}"
        }
    }

    /** Records what the container routes through the workspace action handler. */
    private class RecordingButtonProvider : WorkspaceButtonProvider {
        val actions = mutableListOf<WorkspaceAction>()
        override val state: Flow<WorkspaceButtonViewModel.State?> = flowOf(null)
        override fun executeWorkspaceAction(action: WorkspaceAction) { actions += action }
        override fun navToWorkspaceManager() = Unit
        override fun navToSettings() = Unit
        override fun navToUpgradeButler() = Unit
        override fun createWorkspace(item: QuickCreateItem) = Unit
        override fun createTemplatesWorkspace() = Unit
    }

    @Composable
    private fun Container(
        state: WorkspacesViewModel.State,
        host: RecordingHost,
        visibility: WorkspaceVisibilityTracker,
        onAction: (WorkspaceScreenAction) -> Unit,
        onReachedAppRoot: () -> Unit = {},
    ) {
        PreviewWrapper {
            CompositionLocalProvider(
                LocalWorkspacePageHosts provides mapOf(
                    Workspace.Type.EXPLORER to host,
                    Workspace.Type.APP_DETAILS to host,
                ),
                LocalWorkspacePagerVisibility provides visibility,
            ) {
                // Stands in for MainActivity's press-back-again-to-exit prompt: registered above
                // everything else, so it only runs once nothing in the workspace tree consumed the
                // press. A raw handler on purpose - that is what the real one is, and its LIFO
                // position is the whole point.
                BackHandler(enabled = true) { onReachedAppRoot() }

                ClassicWorkspaceContainer(
                    state = state,
                    onWorkspaceScreenAction = onAction,
                    managerDialogStates = emptyMap(),
                    bannerStates = emptyMap(),
                    onDismissBanner = {},
                    paneLocalModalChains = state.paneLocalModalChains,
                    onShareError = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun `focusing a stacked child scrolls the pager to the tab that owns it`() {
        val host = RecordingHost()
        val infos = listOf(tab(tabA), tab(tabB), paneLocalChild(child, caller = tabB))
        // The selection deliberately still names tab A, as it does the moment a tab-manager
        // selection focuses an off-screen child.
        var current by mutableStateOf(state(infos, focused = tabA, selected = mapOf(0 to tabA)))

        composeTestRule.setContent {
            Container(current, host, WorkspaceVisibilityTracker(), onAction = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).assertIsDisplayed()

        composeTestRule.runOnIdle {
            current = state(infos, focused = child, selected = mapOf(0 to tabA))
        }
        composeTestRule.waitForIdle()

        // The raw child id names no page at all, so without resolving it to its root the pager
        // would sit on tab A forever waiting for an index that never comes. The child only exists
        // inside tab B's page, so seeing it is exactly "the pager moved to tab B".
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(child)).assertIsDisplayed()
    }

    @Test
    fun `the stacked child is the active workspace of its tab's page`() {
        val host = RecordingHost()
        val infos = listOf(tab(tabA), tab(tabB), paneLocalChild(child, caller = tabB))

        composeTestRule.setContent {
            Container(
                state(infos, focused = child, selected = mapOf(0 to tabB)),
                host,
                WorkspaceVisibilityTracker(),
                onAction = {},
            )
        }
        composeTestRule.waitForIdle()

        host.focused[child] shouldBe true
        host.focused[tabB] shouldBe false
    }

    @Test
    fun `a dangling child focus falls back to the pane selection`() {
        val host = RecordingHost()
        val orphan = Workspace.Id()
        val infos = listOf(tab(tabA), tab(tabB), paneLocalChild(orphan, caller = Workspace.Id()))
        var current by mutableStateOf(state(infos, focused = tabA, selected = mapOf(0 to tabA)))

        composeTestRule.setContent {
            Container(current, host, WorkspaceVisibilityTracker(), onAction = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            // Focus points into a chain with no resolvable owner, and nothing repairs that on its
            // own - so the fallback has to, rather than leaving the pager stranded.
            current = state(infos, focused = orphan, selected = mapOf(0 to tabB))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabB)).assertIsDisplayed()
    }

    @Test
    fun `a cyclic focus lands on the pane selection even when the pager disagrees`() {
        val host = RecordingHost()
        val cycleA = Workspace.Id()
        val cycleB = Workspace.Id()
        val infos = listOf(
            tab(tabA),
            tab(tabB),
            paneLocalChild(cycleA, caller = cycleB),
            paneLocalChild(cycleB, caller = cycleA),
        )
        var current by mutableStateOf(state(infos, focused = tabA, selected = mapOf(0 to tabA)))

        composeTestRule.setContent {
            Container(current, host, WorkspaceVisibilityTracker(), onAction = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).assertIsDisplayed()

        composeTestRule.runOnIdle {
            // Pager parked on tab A, selection naming tab B, focus resolving to neither
            current = state(infos, focused = cycleA, selected = mapOf(0 to tabB))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabB)).assertIsDisplayed()
    }

    @Test
    fun `back on the settled page closes its stacked child`() {
        val host = RecordingHost()
        val buttons = RecordingButtonProvider()
        val infos = listOf(tab(tabA), tab(tabB), paneLocalChild(child, caller = tabB))
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalWorkspaceButtonProvider provides buttons) {
                Container(
                    state(infos, focused = child, selected = mapOf(0 to tabB)),
                    host,
                    WorkspaceVisibilityTracker(),
                    onAction = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // The stand-in page registers no handler of its own, so the stack-level fallback answers -
        // which is what the Dialog's onDismissRequest used to do.
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        buttons.actions shouldBe listOf(WorkspaceAction.Close(child))
    }

    /**
     * The mirror image, and the point at which the stack meets `LocalPaneBackActive`: a pane can be
     * the focused one while the pager rests somewhere else, and a stacked child on that off-screen
     * page must not answer Back — it would close a workspace the user is not looking at.
     *
     * The fallback handler gets this for free by going through `WorkspaceBackHandler`, which is
     * gated on the local the pane host publishes; nothing about it is re-implemented here. The
     * `closes its stacked child` case above is the non-vacuous half: the very same handler does
     * answer once the pager is settled on its page.
     */
    @Test
    fun `back does not reach a stacked child on an off-screen page`() {
        val host = RecordingHost()
        val buttons = RecordingButtonProvider()
        val infos = listOf(tab(tabA), tab(tabB), paneLocalChild(child, caller = tabA))
        var reachedAppRoot = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalWorkspaceButtonProvider provides buttons) {
                Container(
                    state(infos, focused = child, selected = mapOf(0 to tabA)),
                    host,
                    WorkspaceVisibilityTracker(),
                    // Swallowed, so focus never follows the swipe: the pager rests on tab B while
                    // the child on tab A still holds focus.
                    onAction = {},
                    onReachedAppRoot = { reachedAppRoot = true },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(child)).assertIsDisplayed()

        composeTestRule.onRoot().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabB)).assertIsDisplayed()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        buttons.actions shouldBe emptyList()
        // Not closing the child is only half of it: the press has to be absorbed rather than fall
        // through, or a second one exits the app from under an open child.
        reachedAppRoot shouldBe false
    }

    /**
     * The placeholder case with a stacked child holding focus. Both back handlers that could answer
     * are keyed on the tab: the child's page handlers are disarmed for being off screen, so the
     * container's is the only one left — and it can only find that tab by resolving the focused
     * child to its owning root, because a child id names no page.
     *
     * App Details is `pausableAsChild`, so unlike a picker it does not withdraw the placeholder,
     * which is what makes this reachable at all.
     */
    @Test
    fun `back on the placeholder returns to the tab owning the focused child`() {
        val host = RecordingHost()
        val buttons = RecordingButtonProvider()
        val infos = listOf(tab(tabA), tab(tabB), paneLocalChild(child, caller = tabB))
        var reachedAppRoot = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalWorkspaceButtonProvider provides buttons) {
                Container(
                    state(infos, focused = child, selected = mapOf(0 to tabB), onDemandCreation = true),
                    host,
                    WorkspaceVisibilityTracker(),
                    onAction = {},
                    onReachedAppRoot = { reachedAppRoot = true },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(child)).assertIsDisplayed()

        // Tab B is the last tab, so one swipe parks the pager on the trailing placeholder.
        composeTestRule.onRoot().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(placeholderTitle, useUnmergedTree = true).assertIsDisplayed()
        // Not assertDoesNotExist: the page has to still be composed off screen, or "nothing
        // answered back" would hold merely because there was nothing left to answer.
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(child)).assertIsNotDisplayed()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        // Back on the placeholder returns to the owning tab, with its child still stacked on it.
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(child)).assertIsDisplayed()
        buttons.actions shouldBe emptyList()
        reachedAppRoot shouldBe false
    }

    /**
     * The child's pointer barrier participates in hit testing so nothing leaks through to the page
     * it covers — but it must consume nothing, because the pager is an ancestor and a consumed down
     * would end tab swiping wherever a child is open.
     */
    @Test
    fun `a horizontal swipe over a stacked child still turns the page`() {
        val host = RecordingHost()
        val infos = listOf(tab(tabA), tab(tabB), paneLocalChild(child, caller = tabA))
        val actions = mutableListOf<WorkspaceScreenAction>()

        composeTestRule.setContent {
            Container(
                state(infos, focused = child, selected = mapOf(0 to tabA)),
                host,
                WorkspaceVisibilityTracker(),
                onAction = { actions += it },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(child)).assertIsDisplayed()

        composeTestRule.onRoot().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        // Two selections, in this order: the finger going down asks for the pane it landed in to be
        // focused (always the pane's own tab, never the child stacked on it), and the gesture then
        // settles on the next page. The second one is what proves the drag reached the pager.
        actions.filterIsInstance<WorkspaceScreenAction.Select>()
            .map { it.id } shouldBe listOf(tabA, tabB)
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabB)).assertIsDisplayed()
    }

    @Test
    fun `the pager publishes the tab it displays`() {
        val host = RecordingHost()
        val visibility = WorkspaceVisibilityTracker()
        val infos = listOf(tab(tabA), tab(tabB))
        var current by mutableStateOf(state(infos, focused = tabA, selected = mapOf(0 to tabA)))

        composeTestRule.setContent {
            Container(current, host, visibility, onAction = {})
        }
        composeTestRule.waitForIdle()

        visibility.visibleIds() shouldBe setOf(tabA)

        composeTestRule.runOnIdle {
            current = state(infos, focused = tabB, selected = mapOf(0 to tabB))
        }
        composeTestRule.waitForIdle()

        visibility.visibleIds() shouldBe setOf(tabB)
    }

    @Test
    fun `a tab list mutation republishes under the new indices`() {
        val host = RecordingHost()
        val visibility = WorkspaceVisibilityTracker()
        var current by mutableStateOf(
            state(listOf(tab(tabA), tab(tabB)), focused = tabA, selected = mapOf(0 to tabA)),
        )

        composeTestRule.setContent {
            Container(current, host, visibility, onAction = {})
        }
        composeTestRule.waitForIdle()
        visibility.visibleIds() shouldBe setOf(tabA)

        composeTestRule.runOnIdle {
            // Same index, different workspace: page indices alone say nothing about identity.
            current = state(listOf(tab(tabB), tab(tabA)), focused = tabB, selected = mapOf(0 to tabB))
        }
        composeTestRule.waitForIdle()

        visibility.visibleIds() shouldBe setOf(tabB)
    }

    @Test
    fun `leaving composition stops publishing`() {
        val host = RecordingHost()
        val visibility = WorkspaceVisibilityTracker()
        val infos = listOf(tab(tabA), tab(tabB))
        var attached by mutableStateOf(true)

        composeTestRule.setContent {
            if (attached) {
                Container(
                    state(infos, focused = tabA, selected = mapOf(0 to tabA)),
                    host,
                    visibility,
                    onAction = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        visibility.visibleIds() shouldBe setOf(tabA)

        // What a rotation into a multi-pane layout does: the classic container goes away and the
        // adaptive layout's pane assignments become the authority again.
        composeTestRule.runOnIdle { attached = false }
        composeTestRule.waitForIdle()

        visibility.visibleIds() shouldBe emptySet()
    }
}
