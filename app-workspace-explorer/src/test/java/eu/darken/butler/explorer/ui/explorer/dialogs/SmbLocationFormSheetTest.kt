package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class SmbLocationFormSheetTest : ComposeTest() {

    private var submitted: SmbLocationFormInput? = null

    private val stored = SmbLocation(
        id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        label = "Home NAS",
        host = "nas.local",
        share = "media",
        username = "darken",
        authType = SmbLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun setSheetContent(state: ExplorerDialogState.SmbLocationForm) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SmbLocationFormSheet(
                        state = state,
                        onDismiss = {},
                        onSubmit = { submitted = it },
                    )
                }
            }
        }
    }

    @Test
    fun `saving is blocked until host share and password are filled in`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm())

        composeTestRule.onNodeWithText("Test & save").performScrollTo().assertIsNotEnabled()

        composeTestRule.onNodeWithText("Server address").performScrollTo().performTextInput("nas.local")
        composeTestRule.onNodeWithText("Share").performScrollTo().performTextInput("media")
        composeTestRule.onNodeWithText("Test & save").performScrollTo().assertIsNotEnabled()

        composeTestRule.onNodeWithText("Password").performScrollTo().performTextInput("hunter2")
        composeTestRule.onNodeWithText("Test & save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `guest access needs no password`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm())

        composeTestRule.onNodeWithText("Server address").performScrollTo().performTextInput("nas.local")
        composeTestRule.onNodeWithText("Share").performScrollTo().performTextInput("media")
        composeTestRule.onNodeWithText("Guest").performScrollTo().performClick()

        composeTestRule.onNodeWithText("Test & save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `the guest toggle hides the credential fields`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm())
        composeTestRule.onNodeWithText("Username").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("Guest").performScrollTo().performClick()

        composeTestRule.onAllNodesWithTextCount("Username") shouldBe 0
    }

    @Test
    fun `editing keeps the stored password when nothing changes`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored))

        composeTestRule.onNodeWithText("Test & save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `changing the username requires the password again`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored))

        composeTestRule.onNodeWithText("darken").performScrollTo().performTextReplacement("someone-else")

        composeTestRule.onNodeWithText("Test & save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `an error is shown inline`() {
        setSheetContent(
            ExplorerDialogState.SmbLocationForm(
                existing = stored,
                error = "nas.local rejected the username or password.".toCaString(),
            )
        )

        composeTestRule
            .onNodeWithText("nas.local rejected the username or password.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `saving is blocked while the connection is being tested`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored, isTesting = true))

        composeTestRule.onNodeWithText("Connecting…").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Test & save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `submitting hands over the entered fields`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm())

        composeTestRule.onNodeWithText("Server address").performScrollTo().performTextInput("nas.local")
        composeTestRule.onNodeWithText("Share").performScrollTo().performTextInput("media")
        composeTestRule.onNodeWithText("Password").performScrollTo().performTextInput("hunter2")
        composeTestRule.onNodeWithText("Test & save").performScrollTo().performClick()

        submitted!!.host shouldBe "nas.local"
        submitted!!.share shouldBe "media"
        submitted!!.password shouldBe "hunter2"
        submitted!!.rememberCredential shouldBe true
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
