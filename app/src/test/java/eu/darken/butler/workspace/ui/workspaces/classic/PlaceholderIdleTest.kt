package eu.darken.butler.workspace.ui.workspaces.classic

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.LocalWorkspacePagerVisibility
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspacesViewModel
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The trailing placeholder's swipe hint pulses forever, so it may only animate while on screen.
 *
 * On a device the pager's cache window keeps the page after the current one composed, which leaves
 * the placeholder composed next to the last tab. Robolectric never runs that prefetch, so the
 * harness pager composes its neighbour through `beyondViewportPageCount` instead.
 *
 * The test rule cancels infinite animations while the main clock auto-advances, so every case
 * pauses the clock before the placeholder is first composed.
 */
class PlaceholderIdleTest : ComposeTest() {

    private lateinit var pagerState: PagerState

    private val placeholderTitle: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.workspace_ondemand_swipe_title)

    /** Page 0 stands in for the last tab, page 1 is the placeholder as the container renders it. */
    @Composable
    private fun Harness(initialPage: Int) {
        PreviewWrapper {
            pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 2 })
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                if (page == 0) {
                    Box(modifier = Modifier.fillMaxSize())
                } else {
                    CreatingWorkspacePlaceholder(isVisible = pagerState.rememberIsPageVisible(page))
                }
            }
        }
    }

    private fun recomposerChanges(): Long = Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    private fun advanceFrames(count: Int) = repeat(count) { composeTestRule.mainClock.advanceTimeByFrame() }

    private fun startPaused(initialPage: Int) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { Harness(initialPage) }
        advanceFrames(5)
    }

    private fun visiblePages() = pagerState.layoutInfo.visiblePagesInfo.map { it.index }

    @Test
    fun `the placeholder composed off screen does no work per frame`() {
        startPaused(initialPage = 0)
        composeTestRule.runOnIdle { visiblePages() shouldBe listOf(0) }

        val before = recomposerChanges()
        advanceFrames(30)
        recomposerChanges() shouldBe before
    }

    @Test
    fun `the placeholder pulses while it is on screen`() {
        startPaused(initialPage = 1)

        val before = recomposerChanges()
        advanceFrames(30)
        recomposerChanges() shouldBeGreaterThan before
    }

    @Test
    fun `the placeholder pulses while it slides into view`() {
        startPaused(initialPage = 0)
        composeTestRule.runOnIdle { runBlocking { pagerState.scrollToPage(0, pageOffsetFraction = 0.3f) } }
        advanceFrames(2)
        composeTestRule.runOnIdle { visiblePages() shouldBe listOf(0, 1) }

        val before = recomposerChanges()
        advanceFrames(30)
        recomposerChanges() shouldBeGreaterThan before
    }

    private object BlankHost : WorkspacePageHostEntry {
        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            Box(modifier = Modifier.fillMaxSize())
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit
    }

    /** The container's own wiring: swiping onto the placeholder starts its pulse. */
    @Test
    fun `the container pulses the placeholder once swiped onto it`() {
        val tab = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            title = "Tab".toCaString(),
            lifecycleState = Workspace.LifecycleState.Ready,
        )
        val state = WorkspacesViewModel.State(
            state = WorkspaceRemote.State(infos = listOf(tab)),
            focusedWorkspace = tab.id,
            selectedWorkspaces = mapOf(0 to tab.id),
            visiblePaneSelections = WorkspacePageManager.State(
                selectedWorkspaces = mapOf(0 to tab.id),
                currentPaneCount = 1,
            ).visiblePaneAssignments,
            isUpgraded = true,
            swipeGesturesEnabled = true,
            onDemandWorkspaceCreation = true,
            currentPaneCount = 1,
        )
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to BlankHost),
                    LocalWorkspacePagerVisibility provides WorkspaceVisibilityTracker(),
                ) {
                    WorkspacePagerContainer(
                        state = state,
                        // Blocks auto-creation, which would swap the hint for the spinner.
                        isOverlayVisible = true,
                        onWorkspaceScreenAction = {},
                        managerDialogStates = emptyMap(),
                        bannerStates = emptyMap(),
                        onDismissBanner = {},
                        onShareError = { _, _ -> },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.onRoot().performTouchInput { swipeLeft() }
        advanceFrames(120)

        val before = recomposerChanges()
        advanceFrames(30)
        recomposerChanges() shouldBeGreaterThan before
        composeTestRule.onNodeWithText(placeholderTitle, useUnmergedTree = true).assertIsDisplayed()
    }
}
