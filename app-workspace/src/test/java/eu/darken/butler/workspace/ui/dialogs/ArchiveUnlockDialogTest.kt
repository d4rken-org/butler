package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class ArchiveUnlockDialogTest : ComposeTest() {

    private val container = LocalPath.build("/storage/emulated/0/Download/photos.7z")
    private val unlocked = mutableListOf<String>()
    private var dismissed = false

    private fun setDialog(attemptFailed: Boolean = false) {
        composeTestRule.setContent {
            PreviewWrapper {
                ArchiveUnlockDialog(
                    issue = PathActionIssue.ArchivePasswordRequired(container = container, attemptFailed = attemptFailed),
                    onDismiss = { dismissed = true },
                    onUnlock = { unlocked += it },
                )
            }
        }
    }

    @Test
    fun `unlock is disabled until a password is typed, then reports it`() {
        setDialog()

        composeTestRule.onNodeWithText("Unlock").assertIsNotEnabled()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("secret")
        composeTestRule.onNodeWithText("Unlock").assertIsEnabled().performClick()

        unlocked shouldContainExactly listOf("secret")
        dismissed shouldBe false
    }

    @Test
    fun `cancel dismisses without unlocking`() {
        setDialog()

        composeTestRule.onNodeWithText("Cancel").performClick()

        dismissed shouldBe true
        unlocked.shouldBeEmpty()
    }

    @Test
    fun `a failed attempt says the password was wrong`() {
        setDialog(attemptFailed = true)

        composeTestRule.onNodeWithText("The password for \"photos.7z\" was incorrect. Try again.").assertIsDisplayed()
    }
}
