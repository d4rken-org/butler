package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
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
import kotlinx.coroutines.CompletableDeferred
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

    private fun setSheetContent(
        state: ExplorerDialogState.SmbLocationForm,
        onRevealPassword: suspend () -> RevealedPassword? = { null },
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SmbLocationFormSheet(
                        state = state,
                        onDismiss = {},
                        onSubmit = { submitted = it },
                        onRevealPassword = onRevealPassword,
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
    fun `changing the domain requires the password again`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored))

        composeTestRule.onNodeWithText("Domain (optional)").performScrollTo().performTextInput("WORKGROUP")

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
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().assertIsNotEnabled()
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

    @Test
    fun `show password reveals the saved password without replacing it on save`() {
        var reveals = 0
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored)) {
            reveals++
            RevealedPassword("saved-password")
        }
        reveals shouldBe 0

        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        composeTestRule.onNodeWithText("saved-password").assertIsDisplayed()
        reveals shouldBe 1
        composeTestRule.onNodeWithText("Test & save").performScrollTo().performClick()
        submitted!!.password shouldBe ""

        composeTestRule.onNodeWithContentDescription("Hide password").performScrollTo().performClick()
        composeTestRule.onNodeWithText("saved-password").assertDoesNotExist()
    }

    @Test
    fun `editing the revealed password submits its replacement`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored)) {
            RevealedPassword("saved-password")
        }
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        composeTestRule.onNodeWithText("saved-password").performTextReplacement("replacement")
        composeTestRule.onNodeWithText("Test & save").performScrollTo().performClick()

        submitted!!.password shouldBe "replacement"
    }

    @Test
    fun `show password reveals newly typed text without reading the vault`() {
        var reveals = 0
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored)) {
            reveals++
            RevealedPassword("saved-password")
        }
        composeTestRule.onNodeWithText("Password").performScrollTo().performTextInput("new-password")
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()

        composeTestRule.onNodeWithText("new-password").assertIsDisplayed()
        reveals shouldBe 0
    }

    @Test
    fun `a delayed reveal does not overwrite a password typed while loading`() {
        val pending = CompletableDeferred<RevealedPassword?>()
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored)) { pending.await() }
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Password").performScrollTo().performTextInput("replacement")
        composeTestRule.runOnIdle { pending.complete(RevealedPassword("saved-password")) }
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()

        composeTestRule.onNodeWithText("replacement").assertIsDisplayed()
        composeTestRule.onNodeWithText("saved-password").assertDoesNotExist()
    }

    @Test
    fun `a failed reveal leaves the controls usable and the password hidden`() {
        val pending = CompletableDeferred<RevealedPassword?>()
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored)) { pending.await() }
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Show password").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Test & save").performScrollTo().assertIsNotEnabled()

        composeTestRule.runOnIdle { pending.complete(null) }

        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Hide password").assertDoesNotExist()
        composeTestRule.onNodeWithText("Test & save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `changing the account drops the revealed password until explicitly revealed again`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored)) {
            RevealedPassword("saved-password")
        }
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        composeTestRule.onNodeWithText("saved-password").assertIsDisplayed()

        composeTestRule.onNodeWithText("darken").performScrollTo().performTextReplacement("someone-else")
        composeTestRule.onNodeWithText("saved-password").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithText("someone-else").performScrollTo().performTextReplacement("darken")
        composeTestRule.onNodeWithText("saved-password").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        composeTestRule.onNodeWithText("saved-password").assertIsDisplayed()
    }

    @Test
    fun `switching to guest while loading discards the pending reveal`() {
        val pending = CompletableDeferred<RevealedPassword?>()
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored)) { pending.await() }
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Guest").performScrollTo().performClick()
        composeTestRule.runOnIdle { pending.complete(RevealedPassword("saved-password")) }
        composeTestRule.onNodeWithText("Username and password").performScrollTo().performClick()

        composeTestRule.onNodeWithText("saved-password").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `clearing a replacement lets show password reveal the saved credential again`() {
        setSheetContent(ExplorerDialogState.SmbLocationForm(existing = stored)) {
            RevealedPassword("saved-password")
        }
        composeTestRule.onNodeWithText("Password").performScrollTo().performTextInput("replacement")
        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        composeTestRule.onNodeWithText("replacement").performTextReplacement("")

        composeTestRule.onNodeWithContentDescription("Show password").performScrollTo().performClick()

        composeTestRule.onNodeWithText("saved-password").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test & save").performScrollTo().performClick()
        submitted!!.password shouldBe ""
    }

}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
