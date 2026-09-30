package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.common.WorkspaceToolbarDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest

// Real font metrics: the default graphics mode measures every line of text at 36dp (see
// ComposeTest), which is taller than the height floor this case is about.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchToolbarCardTest : ComposeTest() {

    private val cardTag = "card"

    @Test
    fun `the collapsed toolbar is as tall as the other workspaces' toolbars`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SearchToolbarCard(
                    modifier = Modifier.testTag(cardTag),
                    workspaceId = Workspace.Id(),
                    state = SearcherWorkspaceViewModel.State.Ready(
                        workspaceState = SearcherWorkspace.State(
                            searchTargets = listOf(
                                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                            ),
                        ),
                        filenameQuery = "*.kt",
                    ),
                    // Split-pane layout: keeps the mascot-bearing workspace button, which
                    // Robolectric cannot rasterise, out of the toolbar cutout.
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    collapsedFraction = 1f,
                    onAction = {},
                )
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightCollapsed
    }

    // region Tapping the collapsed toolbar

    private val filenameQuery = "*.kt"
    private val contentQuery = "fun main"
    private val workspaceId = Workspace.Id()

    private var collapsedFraction by mutableFloatStateOf(1f)
    private var layerActive by mutableStateOf(true)
    private var expandCalls = 0
    private var focusManager: FocusManager? = null

    private fun setHost(onExpand: () -> Unit) {
        composeTestRule.setContent {
            focusManager = LocalFocusManager.current
            PreviewWrapper {
                CompositionLocalProvider(LocalLayerActive provides layerActive) {
                    SearchToolbarCard(
                        workspaceId = workspaceId,
                        state = SearcherWorkspaceViewModel.State.Ready(
                            workspaceState = SearcherWorkspace.State(
                                searchTargets = listOf(
                                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                                ),
                            ),
                            filenameQuery = filenameQuery,
                            contentQuery = contentQuery,
                            contentSearchEnabled = true,
                        ),
                        // Split-pane layout: keeps the mascot-bearing workspace button, which
                        // Robolectric cannot rasterise, out of the toolbar cutout.
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        collapsedFraction = collapsedFraction,
                        onExpand = {
                            expandCalls++
                            onExpand()
                        },
                        onAction = {},
                    )
                }
            }
        }
    }

    private fun tapCollapsedRow() {
        composeTestRule.onNode(hasClickAction() and hasText(filenameQuery, substring = true)).performClick()
        composeTestRule.waitForIdle()
        expandCalls shouldBe 1
    }

    private fun filenameField() = composeTestRule.onNode(hasSetTextAction() and hasText(filenameQuery))

    private fun contentField() = composeTestRule.onNode(hasSetTextAction() and hasText(contentQuery))

    private fun assertNoFieldFocused() {
        filenameField().assertIsNotFocused()
        contentField().assertIsNotFocused()
    }

    private fun moveBarTo(fraction: Float) {
        collapsedFraction = fraction
        composeTestRule.waitForIdle()
    }

    @Test
    fun `tapping the collapsed row asks the bar to expand`() {
        setHost(onExpand = {})

        tapCollapsedRow()
    }

    @Test
    fun `a tap that expands the bar focuses the filename field`() {
        setHost(onExpand = { collapsedFraction = 0f })

        tapCollapsedRow()

        filenameField().assertIsFocused()
        contentField().assertIsNotFocused()
    }

    @Test
    fun `a tap that loses to a collapsing scroll does not focus on a later reveal`() {
        setHost(onExpand = { collapsedFraction = 0.8f })

        tapCollapsedRow()
        moveBarTo(1f)
        moveBarTo(0f)

        assertNoFieldFocused()
    }

    @Test
    fun `a tap does not focus once the pane is no longer active`() {
        setHost(onExpand = { collapsedFraction = 0.8f })

        tapCollapsedRow()
        layerActive = false
        composeTestRule.waitForIdle()
        moveBarTo(0f)

        assertNoFieldFocused()
    }

    @Test
    fun `an ordinary re-expand after a tap-expand does not focus again`() {
        setHost(onExpand = { collapsedFraction = 0f })
        tapCollapsedRow()
        filenameField().assertIsFocused()

        composeTestRule.runOnIdle { focusManager!!.clearFocus(force = true) }
        moveBarTo(1f)
        moveBarTo(0f)

        assertNoFieldFocused()
    }

    // endregion
}
