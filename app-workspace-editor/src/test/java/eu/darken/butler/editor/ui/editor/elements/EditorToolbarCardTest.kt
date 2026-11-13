package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.ui.editor.EditorPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.common.WorkspaceToolbarDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest

/**
 * The toolbar's single save button: a scratch buffer saves straight through Save-As, a file-backed
 * document opens a menu offering Save and Save as… with their own enablement. While collapsed, the
 * whole one-line card is a single target that asks the bar to expand.
 */
class EditorToolbarCardTest : ComposeTest() {

    private fun setCard(
        isModified: Boolean = true,
        isReadOnly: Boolean = false,
        isBackingLost: Boolean = false,
        hasContent: Boolean = true,
        hasFile: Boolean = true,
        paneFocused: Boolean = true,
        onAction: (EditorPageAction) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = paneFocused) {
                    // Multi-pane: a single-pane card carries a cutout, and CutoutCard's
                    // measurement subcomposition would put every node in the tree twice
                    EditorToolbarCard(
                        workspaceId = Workspace.Id(),
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        title = "example.txt".toCaString(),
                        subTitle = "/storage/emulated/0".toCaString(),
                        isModified = isModified,
                        isReadOnly = isReadOnly,
                        isBackingLost = isBackingLost,
                        progress = null,
                        hasContent = hasContent,
                        hasFile = hasFile,
                        canUndo = false,
                        canRedo = false,
                        onAction = onAction,
                    )
                }
            }
        }
    }

    @Test
    fun `a modified never-saved buffer saves through save-as without a menu`() {
        val actions = mutableListOf<EditorPageAction>()
        setCard(hasFile = false, hasContent = false, onAction = { actions += it })

        composeTestRule.onNodeWithContentDescription("Save").performClick()
        composeTestRule.waitForIdle()

        actions shouldBe listOf(EditorPageAction.File.SaveAs)
        composeTestRule.onNodeWithText("Save as…").assertDoesNotExist()
    }

    @Test
    fun `a blank never-saved buffer has no save button`() {
        setCard(isModified = false, hasContent = false, hasFile = false)

        composeTestRule.onNodeWithContentDescription("Save").assertDoesNotExist()
    }

    @Test
    fun `a modified file-backed document offers both save actions`() {
        val actions = mutableListOf<EditorPageAction>()
        setCard(onAction = { actions += it })

        composeTestRule.onNodeWithContentDescription("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save").assertIsEnabled()
        composeTestRule.onNodeWithText("Save as…").assertIsEnabled()
        actions shouldBe emptyList()
    }

    @Test
    fun `an unmodified file-backed document can only be saved elsewhere`() {
        setCard(isModified = false)

        composeTestRule.onNodeWithContentDescription("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save as…").assertIsEnabled()
    }

    @Test
    fun `a read-only document can only be saved elsewhere`() {
        setCard(isModified = false, isReadOnly = true)

        composeTestRule.onNodeWithContentDescription("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save as…").assertIsEnabled()
    }

    @Test
    fun `a document whose backing file vanished has no save button`() {
        setCard(isReadOnly = true, isBackingLost = true)

        composeTestRule.onNodeWithContentDescription("Save").assertDoesNotExist()
    }

    @Test
    fun `picking a menu item dispatches it and closes the menu`() {
        val actions = mutableListOf<EditorPageAction>()
        setCard(onAction = { actions += it })

        composeTestRule.onNodeWithContentDescription("Save").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        actions shouldBe listOf(EditorPageAction.File.Save)
        composeTestRule.onNodeWithText("Save as…").assertDoesNotExist()
    }

    @Test
    fun `the menu closes when its pane stops being focused`() {
        var paneFocused by mutableStateOf(true)
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = paneFocused) {
                    EditorToolbarCard(
                        workspaceId = Workspace.Id(),
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        title = "example.txt".toCaString(),
                        subTitle = "/storage/emulated/0".toCaString(),
                        isModified = true,
                        progress = null,
                        hasContent = true,
                        hasFile = true,
                        canUndo = false,
                        canRedo = false,
                        onAction = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Save").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save as…").assertExists()

        composeTestRule.runOnIdle { paneFocused = false }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save as…").assertDoesNotExist()
    }

    private val cardTag = "card"
    private val title = "example.txt"
    private var expandCalls = 0

    private fun setExpandableCard(
        collapsedFraction: Float = 1f,
        withExpand: Boolean = true,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    EditorToolbarCard(
                        modifier = Modifier.testTag(cardTag),
                        workspaceId = Workspace.Id(),
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        title = title.toCaString(),
                        subTitle = "/storage/emulated/0".toCaString(),
                        isModified = true,
                        progress = null,
                        hasContent = true,
                        hasFile = true,
                        canUndo = false,
                        canRedo = false,
                        onAction = {},
                        collapsedFraction = collapsedFraction,
                        onExpand = if (withExpand) {
                            { expandCalls++ }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    // Real font metrics: the default graphics mode measures every line of text at 36dp (see
    // ComposeTest), which is taller than the height floor this case is about.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `the tappable collapsed toolbar keeps the collapsed toolbar height`() {
        setExpandableCard()

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightCollapsed
    }

    @Test
    fun `tapping the collapsed title asks the bar to expand`() {
        setExpandableCard()

        composeTestRule.onNodeWithText(title, useUnmergedTree = true).performTouchInput { click() }
        composeTestRule.waitForIdle()

        expandCalls shouldBe 1
    }

    @Test
    fun `tapping the collapsed file icon asks the bar to expand`() {
        setExpandableCard()

        composeTestRule.onNode(hasClickAction() and hasText(title)).performTouchInput {
            // The icon sits after the row's 8dp start padding and is 24dp wide.
            click(Offset(20.dp.toPx(), centerY))
        }
        composeTestRule.waitForIdle()

        expandCalls shouldBe 1
    }

    @Test
    fun `tapping the expanded title does not ask the bar to expand`() {
        setExpandableCard(collapsedFraction = 0f)

        composeTestRule.onNodeWithText(title, useUnmergedTree = true).performTouchInput { click() }
        composeTestRule.waitForIdle()

        expandCalls shouldBe 0
    }

    @Test
    fun `the collapsed title is not tappable without an expand handler`() {
        setExpandableCard(withExpand = false)

        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction() and hasText(title)).assertCountEquals(0)
    }
}
