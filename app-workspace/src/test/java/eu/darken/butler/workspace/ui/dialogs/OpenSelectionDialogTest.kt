package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.OpenSelectionMode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class OpenSelectionDialogTest : ComposeTest() {

    private val viewHere = "View here"
    private val viewInTab = "View in new tab"
    private val eachInTab = "Open each in its own tab"
    private val unavailableReason = "Folders can't be shown in the viewer."

    private val selected = mutableListOf<OpenSelectionMode>()
    private var dismissed = false

    private fun setDialog(itemCount: Int = 3, viewerModesAvailable: Boolean) {
        composeTestRule.setContent {
            PreviewWrapper {
                OpenSelectionDialog(
                    itemCount = itemCount,
                    viewerModesAvailable = viewerModesAvailable,
                    onDismiss = { dismissed = true },
                    onSelect = { selected += it },
                )
            }
        }
    }

    @Test
    fun `every mode is offered and reports itself when viewer modes are available`() {
        setDialog(viewerModesAvailable = true)

        composeTestRule.onNodeWithText(viewHere).assertIsDisplayed()
        composeTestRule.onNodeWithText(viewInTab).assertIsDisplayed()
        composeTestRule.onNodeWithText(eachInTab).assertIsDisplayed()
        composeTestRule.onNodeWithText(unavailableReason).assertDoesNotExist()

        composeTestRule.onNodeWithText(viewHere).performClick()
        composeTestRule.onNodeWithText(viewInTab).performClick()
        composeTestRule.onNodeWithText(eachInTab).performClick()

        selected shouldContainExactly listOf(
            OpenSelectionMode.VIEW_HERE,
            OpenSelectionMode.VIEW_IN_TAB,
            OpenSelectionMode.EACH_IN_TAB,
        )
        dismissed shouldBe false
    }

    @Test
    fun `unavailable viewer modes are disabled with a reason while each-in-tab still works`() {
        setDialog(viewerModesAvailable = false)

        composeTestRule.onNodeWithText(viewHere).assertIsNotEnabled()
        composeTestRule.onNodeWithText(viewInTab).assertIsNotEnabled()
        composeTestRule.onNodeWithText(unavailableReason).assertIsDisplayed()
        composeTestRule.onNodeWithText(eachInTab).assertIsEnabled()

        composeTestRule.onNodeWithText(viewHere).performClick()
        composeTestRule.onNodeWithText(viewInTab).performClick()
        selected.shouldBeEmpty()

        composeTestRule.onNodeWithText(eachInTab).performClick()
        selected shouldContainExactly listOf(OpenSelectionMode.EACH_IN_TAB)
    }

    @Test
    fun `the title shows the item count`() {
        setDialog(itemCount = 7, viewerModesAvailable = true)

        composeTestRule.onNodeWithText("Open 7 items").assertIsDisplayed()
    }

    @Test
    fun `cancel dismisses without picking a mode`() {
        setDialog(viewerModesAvailable = true)

        composeTestRule.onNodeWithText("Cancel").performClick()

        dismissed shouldBe true
        selected.shouldBeEmpty()
    }
}
