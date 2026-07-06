package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.Test
import testhelpers.ComposeTest

class EditorBannerGroupTest : ComposeTest() {

    private fun setGroup(
        error: Throwable? = null,
        backupNames: List<String> = emptyList(),
        showBackupNotice: Boolean = false,
        isBinary: Boolean = false,
        onDismissError: () -> Unit = {},
        onDismissBackupNotice: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                EditorBannerGroup(
                    error = error,
                    backupNames = backupNames,
                    showBackupNotice = showBackupNotice,
                    isBinary = isBinary,
                    onDismissError = onDismissError,
                    onDismissBackupNotice = onDismissBackupNotice,
                )
            }
        }
    }

    @Test
    fun `error banner shows message and dismiss triggers callback`() {
        var dismissed = false
        setGroup(
            error = RuntimeException("Disk full"),
            onDismissError = { dismissed = true },
        )

        composeTestRule.onNodeWithText("Disk full").assertExists()
        composeTestRule.onAllNodesWithContentDescription("Dismiss")[0].performClick()
        dismissed.shouldBeTrue()
    }

    @Test
    fun `backup banner shows artifact name and dismiss triggers callback`() {
        var dismissed = false
        setGroup(
            showBackupNotice = true,
            backupNames = listOf("notes.txt.butler-save-bak-1a2b3c4d"),
            onDismissBackupNotice = { dismissed = true },
        )

        composeTestRule.onNodeWithText("notes.txt.butler-save-bak-1a2b3c4d", substring = true).assertExists()
        composeTestRule.onAllNodesWithContentDescription("Dismiss")[0].performClick()
        dismissed.shouldBeTrue()
    }

    @Test
    fun `all banners can be visible simultaneously`() {
        setGroup(
            error = RuntimeException("Disk full"),
            showBackupNotice = true,
            backupNames = listOf("notes.txt.butler-save-bak-1a2b3c4d"),
            isBinary = true,
        )

        composeTestRule.onNodeWithText("Disk full").assertExists()
        composeTestRule.onNodeWithText("notes.txt.butler-save-bak-1a2b3c4d", substring = true).assertExists()
        composeTestRule.onNodeWithText("Binary file — read-only view").assertExists()
        composeTestRule.onAllNodesWithContentDescription("Dismiss").assertCountEquals(2)
    }

    @Test
    fun `nothing rendered when no notices are active`() {
        setGroup()

        composeTestRule.onAllNodesWithContentDescription("Dismiss").assertCountEquals(0)
        composeTestRule.onNodeWithText("Binary file — read-only view").assertDoesNotExist()
    }

    @Test
    fun `error with blank message falls back to unknown error label`() {
        setGroup(error = RuntimeException(""))

        composeTestRule.onNodeWithText("Unknown error", substring = true).assertExists()
    }
}
