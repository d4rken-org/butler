package eu.darken.butler.workspace.ui.settings

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.layout.RailButtonPlacement
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * Settings offers three surfaces, not the eight stored modes: a geometry pinned from the Butler menu
 * shows up as the Adaptive row, with the geometry named in the item's value.
 *
 * The landscape item is parked on SINGLE unless a test says otherwise, so the only labels colliding
 * between the two preference items and the dialog rows are ones no assertion resolves by text alone.
 */
@Config(qualifiers = "w400dp-h800dp")
class WorkspaceSettingsLayoutTest : ComposeTest() {

    private val portraitModes = mutableListOf<WorkspacePanelMode>()
    private val landscapeModes = mutableListOf<WorkspacePanelMode>()
    private val railButtonPlacements = mutableListOf<RailButtonPlacement>()

    private fun setScreen(
        portraitMode: WorkspacePanelMode,
        railButtonPlacement: RailButtonPlacement = RailButtonPlacement.LEADING,
        landscapeMode: WorkspacePanelMode = WorkspacePanelMode.SINGLE,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceSettingsScreen(
                    state = WorkspaceSettingsViewModel.State(
                        swipeGesturesEnabled = true,
                        onDemandWorkspaceCreation = true,
                        livePreview = true,
                        layoutModePortrait = portraitMode,
                        layoutModeLandscape = landscapeMode,
                        paneClickToFocus = true,
                        railButtonPlacement = railButtonPlacement,
                        sessionRestoreEnabled = true,
                    ),
                    onNavigateUp = {},
                    onToggleSwipeGestures = {},
                    onToggleOnDemandWorkspaceCreation = {},
                    onSetDefaultNewTabType = {},
                    onToggleLivePreview = {},
                    onSetLayoutModePortrait = { portraitModes += it },
                    onSetLayoutModeLandscape = { landscapeModes += it },
                    onTogglePaneClickToFocus = {},
                    onSetRailButtonPlacement = { railButtonPlacements += it },
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
    fun `tapping adaptive from classic stores the unpinned adaptive mode`() {
        openPortraitDialog(WorkspacePanelMode.SINGLE)

        composeTestRule.onNode(isSelectable() and hasText("Adaptive")).performClick()

        portraitModes shouldBe listOf(WorkspacePanelMode.ADAPTIVE)
        landscapeModes shouldBe emptyList()
    }

    @Test
    fun `re-tapping adaptive in portrait keeps the pinned geometry`() {
        openPortraitDialog(WorkspacePanelMode.DUAL_VERTICAL)

        composeTestRule.onNode(isSelectable() and hasText("Adaptive")).performClick()

        portraitModes shouldBe emptyList()
        landscapeModes shouldBe emptyList()
        composeTestRule.onAllNodes(isSelectable()).assertCountEquals(0)
    }

    @Test
    fun `re-tapping adaptive in landscape keeps the pinned geometry`() {
        setScreen(portraitMode = WorkspacePanelMode.SINGLE, landscapeMode = WorkspacePanelMode.DUAL_VERTICAL)
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(LANDSCAPE_TITLE))
        composeTestRule.onNodeWithText(LANDSCAPE_TITLE).performClick()

        composeTestRule.onNode(isSelectable() and hasText("Adaptive")).performClick()

        portraitModes shouldBe emptyList()
        landscapeModes shouldBe emptyList()
        composeTestRule.onAllNodes(isSelectable()).assertCountEquals(0)
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

    @Test
    fun `the rail button row names the stored placement`() {
        setScreen(WorkspacePanelMode.AUTO, RailButtonPlacement.TRAILING)

        railButtonRow().assertTextContains("End")
    }

    @Test
    fun `picking a rail button placement reports it`() {
        setScreen(WorkspacePanelMode.AUTO, RailButtonPlacement.TRAILING)

        railButtonRow().performClick()
        composeTestRule.onNode(isSelectable() and hasText("Start")).performClick()

        railButtonPlacements shouldBe listOf(RailButtonPlacement.LEADING)
    }

    /** The row sits below the fold of the settings list, so it is scrolled to before it is read. */
    private fun railButtonRow(): SemanticsNodeInteraction {
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(RAIL_BUTTON_TITLE))
        return composeTestRule.onNodeWithText(RAIL_BUTTON_TITLE)
    }

    companion object {
        private const val RAIL_BUTTON_TITLE = "Butler button"
        private const val LANDSCAPE_TITLE = "Landscape layout mode"
    }
}
