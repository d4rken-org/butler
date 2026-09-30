package eu.darken.butler.apps.ui.apps.elements

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
import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
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
class AppsToolbarCardTest : ComposeTest() {

    private val cardTag = "card"

    @Test
    fun `the collapsed toolbar is as tall as the other workspaces' toolbars`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppsToolbarCard(
                    modifier = Modifier.testTag(cardTag),
                    workspaceId = Workspace.Id(),
                    searchQuery = TextFieldValue("Chrome"),
                    onSearchQueryChange = {},
                    filterConfig = TagFilterConfig(),
                    onFilterAdd = {},
                    onFilterRemove = { _, _ -> },
                    // Split-pane layout: keeps the mascot-bearing workspace button, which
                    // Robolectric cannot rasterise, out of the toolbar cutout.
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    collapsedFraction = 1f,
                )
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightCollapsed
    }

    // region Tapping the collapsed toolbar

    private val query = "Chrome"

    private var collapsedFraction by mutableFloatStateOf(1f)
    private var layerActive by mutableStateOf(true)
    private var expandCalls = 0
    private var focusManager: FocusManager? = null

    private fun setHost(onExpand: () -> Unit) {
        composeTestRule.setContent {
            focusManager = LocalFocusManager.current
            PreviewWrapper {
                CompositionLocalProvider(LocalLayerActive provides layerActive) {
                    AppsToolbarCard(
                        workspaceId = Workspace.Id(),
                        searchQuery = TextFieldValue(query),
                        onSearchQueryChange = {},
                        filterConfig = TagFilterConfig(),
                        onFilterAdd = {},
                        onFilterRemove = { _, _ -> },
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        collapsedFraction = collapsedFraction,
                        onExpand = {
                            expandCalls++
                            onExpand()
                        },
                    )
                }
            }
        }
    }

    private fun tapCollapsedRow() {
        composeTestRule.onNode(hasClickAction() and hasText(query)).performClick()
        composeTestRule.waitForIdle()
        expandCalls shouldBe 1
    }

    private fun searchField() = composeTestRule.onNode(hasSetTextAction() and hasText(query))

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
    fun `a tap that expands the bar focuses the search field`() {
        setHost(onExpand = { collapsedFraction = 0f })

        tapCollapsedRow()

        searchField().assertIsFocused()
    }

    @Test
    fun `a tap that loses to a collapsing scroll does not focus on a later reveal`() {
        setHost(onExpand = { collapsedFraction = 0.8f })

        tapCollapsedRow()
        moveBarTo(1f)
        moveBarTo(0f)

        searchField().assertIsNotFocused()
    }

    @Test
    fun `a tap does not focus once the pane is no longer active`() {
        setHost(onExpand = { collapsedFraction = 0.8f })

        tapCollapsedRow()
        layerActive = false
        composeTestRule.waitForIdle()
        moveBarTo(0f)

        searchField().assertIsNotFocused()
    }

    @Test
    fun `an ordinary re-expand after a tap-expand does not focus again`() {
        setHost(onExpand = { collapsedFraction = 0f })
        tapCollapsedRow()
        searchField().assertIsFocused()

        composeTestRule.runOnIdle { focusManager!!.clearFocus(force = true) }
        moveBarTo(1f)
        moveBarTo(0f)

        searchField().assertIsNotFocused()
    }

    // endregion
}
