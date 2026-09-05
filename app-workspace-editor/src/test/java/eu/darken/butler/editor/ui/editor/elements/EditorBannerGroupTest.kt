package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
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
        showExternalChange: Boolean = false,
        backupNames: List<String> = emptyList(),
        showBackupNotice: Boolean = false,
        isBinary: Boolean = false,
        onDismissError: () -> Unit = {},
        onReloadFromDisk: () -> Unit = {},
        onDismissExternalChange: () -> Unit = {},
        onDismissBackupNotice: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                EditorBannerGroup(
                    error = error,
                    showExternalChange = showExternalChange,
                    backupNames = backupNames,
                    showBackupNotice = showBackupNotice,
                    isBinary = isBinary,
                    onDismissError = onDismissError,
                    onReloadFromDisk = onReloadFromDisk,
                    onDismissExternalChange = onDismissExternalChange,
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
    fun `external change banner triggers reload callback`() {
        var reloaded = false
        setGroup(
            showExternalChange = true,
            onReloadFromDisk = { reloaded = true },
        )

        composeTestRule.onNodeWithText("File changed on disk").assertExists()
        composeTestRule.onNodeWithText("Reload").performClick()
        reloaded.shouldBeTrue()
    }

    @Test
    fun `external change banner triggers keep-editing callback`() {
        var kept = false
        setGroup(
            showExternalChange = true,
            onDismissExternalChange = { kept = true },
        )

        composeTestRule.onNodeWithText("Keep editing").performClick()
        kept.shouldBeTrue()
    }

    @Test
    fun `all banners can be visible simultaneously`() {
        setGroup(
            error = RuntimeException("Disk full"),
            showExternalChange = true,
            showBackupNotice = true,
            backupNames = listOf("notes.txt.butler-save-bak-1a2b3c4d"),
            isBinary = true,
        )

        composeTestRule.onNodeWithText("Disk full").assertExists()
        composeTestRule.onNodeWithText("File changed on disk").assertExists()
        composeTestRule.onNodeWithText("notes.txt.butler-save-bak-1a2b3c4d", substring = true).assertExists()
        composeTestRule.onNodeWithText("Editing as text isn't supported").assertExists()
        composeTestRule.onAllNodesWithContentDescription("Dismiss").assertCountEquals(2)
    }

    @Test
    fun `banner group scrolls when capped by short viewports`() {
        setGroup(isBinary = true)

        composeTestRule.onNode(hasScrollAction()).assertExists()
    }

    @Test
    fun `nothing rendered when no notices are active`() {
        setGroup()

        composeTestRule.onAllNodesWithContentDescription("Dismiss").assertCountEquals(0)
        composeTestRule.onNodeWithText("Editing as text isn't supported").assertDoesNotExist()
    }

    @Test
    fun `error with blank message falls back to unknown error label`() {
        setGroup(error = RuntimeException(""))

        composeTestRule.onNodeWithText("Unknown error", substring = true).assertExists()
    }
}
