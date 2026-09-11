package eu.darken.butler.workspace.ui.settings

import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * Settings offers three surfaces, not the eight stored modes: a geometry pinned from the rail shows
 * up as the Adaptive row, with the geometry named in the item's value.
 *
 * The landscape item is parked on SINGLE throughout, so the only labels colliding between the two
 * preference items and the dialog rows are ones no assertion resolves by text alone.
 */
@Config(qualifiers = "w400dp-h800dp")
class WorkspaceSettingsLayoutTest : ComposeTest() {

    private val portraitModes = mutableListOf<WorkspacePanelMode>()

    private fun setScreen(portraitMode: WorkspacePanelMode) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceSettingsScreen(
                    state = WorkspaceSettingsViewModel.State(
                        swipeGesturesEnabled = true,
                        onDemandWorkspaceCreation = true,
                        livePreview = true,
                        layoutModePortrait = portraitMode,
                        layoutModeLandscape = WorkspacePanelMode.SINGLE,
                        paneClickToFocus = true,
                        sessionRestoreEnabled = true,
                    ),
                    onNavigateUp = {},
                    onToggleSwipeGestures = {},
                    onToggleOnDemandWorkspaceCreation = {},
                    onSetDefaultNewTabType = {},
                    onToggleLivePreview = {},
                    onSetLayoutModePortrait = { portraitModes += it },
                    onSetLayoutModeLandscape = {},
                    onTogglePaneClickToFocus = {},
                    onToggleSessionRestore = {},
                    onToggleAutoPause = {},
                    onSetAutoPauseIdleTimeout = {},
                    onToggleUndoClose = {},
                )
            }
        }
    }

    private fun openPortraitDialog(portraitMode: WorkspacePanelMode = WorkspacePanelMode.AUTO) {
        setScreen(portraitMode)
        composeTestRule.onNodeWithText("Portrait layout mode").performClick()
    }

    @Test
    fun `the portrait dialog offers the three surfaces and no geometry`() {
        openPortraitDialog()

        val rows = composeTestRule.onAllNodes(isSelectable())
        rows.assertCountEquals(3)
        rows.assertAny(hasText("Automatic"))
        rows.assertAny(hasText("Classic"))
        rows.assertAny(hasText("Adaptive"))

        composeTestRule.onNodeWithText("Dual vertical").assertDoesNotExist()
        composeTestRule.onNodeWithText("Single with tab rail").assertDoesNotExist()
        composeTestRule.onNodeWithText("Quad grid").assertDoesNotExist()
    }

    @Test
    fun `tapping adaptive stores the unpinned adaptive mode`() {
        openPortraitDialog()

        composeTestRule.onNode(isSelectable() and hasText("Adaptive")).performClick()

        portraitModes shouldBe listOf(WorkspacePanelMode.ADAPTIVE)
    }

    @Test
    fun `tapping classic stores the single mode`() {
        openPortraitDialog()

        composeTestRule.onNode(isSelectable() and hasText("Classic")).performClick()

        portraitModes shouldBe listOf(WorkspacePanelMode.SINGLE)
    }

    @Test
    fun `a pinned geometry reads as adaptive with the geometry named`() {
        setScreen(WorkspacePanelMode.DUAL_VERTICAL)

        composeTestRule.onNodeWithText("Adaptive (Dual vertical)").assertIsDisplayed()

        composeTestRule.onNodeWithText("Portrait layout mode").performClick()
        composeTestRule.onNode(isSelectable() and hasText("Adaptive")).assertIsSelected()
    }

    @Test
    fun `the single-with-rail geometry also reads as adaptive`() {
        setScreen(WorkspacePanelMode.SINGLE_RAIL)

        composeTestRule.onNodeWithText("Adaptive (Single with tab rail)").assertIsDisplayed()
    }
}
