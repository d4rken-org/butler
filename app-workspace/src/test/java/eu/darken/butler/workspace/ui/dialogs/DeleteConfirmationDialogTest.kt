package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class DeleteConfirmationDialogTest : ComposeTest() {

    private val localFile = LocalPath.build("/storage/emulated/0/Download/local.txt")
    private val safFile = SAFPath.build(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        "saf.txt",
    )
    private val safFile2 = SAFPath.build(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        "saf2.txt",
    )

    private val toggleLabel = "Delete permanently instead"
    private val partialNotice = "Some items can't be moved to trash and will be handled separately."
    private val moveAction = "Move"
    private val deleteAction = "Delete Permanently"
    private val trashTitle = "Move to Trash?"
    private val deleteTitle = "Delete item?"

    private var confirmed: Pair<Set<APath<*>>, Boolean>? = null

    private fun setDialog(
        items: Set<APath<*>>,
        trashEnabled: Boolean = true,
        initialPermanentDelete: Boolean = false,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                DeleteConfirmationDialog(
                    items = items,
                    trashEnabled = trashEnabled,
                    initialPermanentDelete = initialPermanentDelete,
                    onDismiss = {},
                    onConfirm = { confirmedItems, forcePermDelete ->
                        confirmed = confirmedItems to forcePermDelete
                    },
                )
            }
        }
    }

    @Test
    fun `toggling the checkbox flips the confirm payload and the button label`() {
        setDialog(items = setOf(localFile))

        composeTestRule.onNodeWithText(trashTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText(moveAction).assertIsDisplayed()
        composeTestRule.onNodeWithText(toggleLabel).assertIsOff()

        composeTestRule.onNodeWithText(toggleLabel).performClick()

        composeTestRule.onNodeWithText(toggleLabel).assertIsOn()
        composeTestRule.onNodeWithText(deleteTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText(deleteAction).performClick()

        confirmed shouldBe (setOf(localFile) to true)
    }

    @Test
    fun `an untrashable selection hides the toggle and confirms permanent delete`() {
        setDialog(items = setOf(safFile, safFile2))

        composeTestRule.onNodeWithText(toggleLabel).assertDoesNotExist()
        composeTestRule.onNodeWithText(deleteAction).assertIsDisplayed()

        composeTestRule.onNodeWithText(deleteAction).performClick()

        confirmed shouldBe (setOf<APath<*>>(safFile, safFile2) to true)
    }

    @Test
    fun `a mixed selection keeps trash wording and warns about the untrashable part`() {
        setDialog(items = setOf(localFile, safFile))

        composeTestRule.onNodeWithText(moveAction).assertIsDisplayed()
        composeTestRule.onNodeWithText(partialNotice).assertIsDisplayed()
        composeTestRule.onNodeWithText(toggleLabel).assertIsOff()

        composeTestRule.onNodeWithText(moveAction).performClick()

        confirmed shouldBe (setOf<APath<*>>(localFile, safFile) to false)
    }

    @Test
    fun `a replacement request does not inherit the previous choice`() {
        var items by mutableStateOf<Set<APath<*>>>(setOf(localFile))
        composeTestRule.setContent {
            PreviewWrapper {
                DeleteConfirmationDialog(
                    items = items,
                    trashEnabled = true,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithText(toggleLabel).performClick()
        composeTestRule.onNodeWithText(toggleLabel).assertIsOn()

        items = setOf(LocalPath.build("/storage/emulated/0/Download/other.txt"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(toggleLabel).assertIsOff()
    }

    @Test
    fun `unchecking a seeded permanent delete survives recreation`() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            PreviewWrapper {
                DeleteConfirmationDialog(
                    items = setOf(localFile),
                    trashEnabled = true,
                    initialPermanentDelete = true,
                    onDismiss = {},
                    onConfirm = { confirmedItems, forcePermDelete ->
                        confirmed = confirmedItems to forcePermDelete
                    },
                )
            }
        }

        composeTestRule.onNodeWithText(toggleLabel).assertIsOn()
        composeTestRule.onNodeWithText(toggleLabel).performClick()
        composeTestRule.onNodeWithText(toggleLabel).assertIsOff()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText(toggleLabel).assertIsOff()
        composeTestRule.onNodeWithText(moveAction).performClick()

        confirmed shouldBe (setOf(localFile) to false)
    }
}
