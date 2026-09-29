package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class SftpLocationFormSheetTest : ComposeTest() {

    private var submitted: SftpLocationFormInput? = null
    private var keyPicks = 0

    private val stored = SftpLocation(
        id = Uuid.parse("66666666-7777-8888-9999-000000000000"),
        label = "Build server",
        host = "build.lan",
        username = "darken",
        basePath = "/srv",
        authType = SftpLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        hostKey = TrustedHostKey(
            type = "ssh-ed25519",
            blob = ByteArray(51),
            fingerprint = "SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM",
        ),
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun setSheetContent(state: ExplorerDialogState.SftpLocationForm) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SftpLocationFormSheet(
                        state = state,
                        onDismiss = {},
                        onSubmit = { submitted = it },
                        onPickKeyFile = { keyPicks++ },
                    )
                }
            }
        }
    }

    // Disabled text fields drop their SetText action but keep their editable text
    private val isTextField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    private fun field(label: String): SemanticsNodeInteraction =
        composeTestRule.onNode(isTextField and hasText(label)).performScrollTo()

    private fun saveButton() = composeTestRule.onNodeWithText("Test & save").performScrollTo()

    @Test
    fun `saving is blocked until server, username and password are filled in`() {
        setSheetContent(ExplorerDialogState.SftpLocationForm())
        saveButton().assertIsNotEnabled()

        field("Server address").performTextInput("build.lan")
        field("Username").performTextInput("darken")
        saveButton().assertIsNotEnabled()

        field("Password").performTextInput("hunter2")
        saveButton().assertIsEnabled()
    }

    @Test
    fun `the port starts at 22 and the base path explains its three forms`() {
        setSheetContent(ExplorerDialogState.SftpLocationForm())

        field("Port").assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction() and hasText("22")).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "Empty: the server's start folder. Starting with /: an absolute path. " +
                    "Anything else: relative to the start folder.",
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `key sign-in needs a picked key file`() {
        setSheetContent(ExplorerDialogState.SftpLocationForm())
        field("Server address").performTextInput("build.lan")
        field("Username").performTextInput("darken")

        composeTestRule.onNodeWithText("Private key").performScrollTo().performClick()

        composeTestRule.onNodeWithText("No key file chosen").performScrollTo().assertIsDisplayed()
        saveButton().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Choose file").performScrollTo().performClick()
        keyPicks shouldBe 1
    }

    @Test
    fun `a picked key file is shown by name and allows saving`() {
        var state by mutableStateOf(ExplorerDialogState.SftpLocationForm())
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SftpLocationFormSheet(
                        state = state,
                        onDismiss = {},
                        onSubmit = { submitted = it },
                        onPickKeyFile = { keyPicks++ },
                    )
                }
            }
        }
        field("Server address").performTextInput("build.lan")
        field("Username").performTextInput("darken")
        composeTestRule.onNodeWithText("Private key").performScrollTo().performClick()
        saveButton().assertIsNotEnabled()

        state = state.copy(keyFileName = "id_ed25519")

        composeTestRule.onNodeWithText("id_ed25519").performScrollTo().assertIsDisplayed()
        saveButton().assertIsEnabled()
        saveButton().performClick()
        submitted!!.authType shouldBe SftpLocation.AuthType.PRIVATE_KEY
    }

    @Test
    fun `editing keeps the stored password when the account stays the same`() {
        setSheetContent(ExplorerDialogState.SftpLocationForm(mode = SftpFormMode.EDIT, existing = stored))

        saveButton().assertIsEnabled()

        field("darken").performTextReplacement("root")
        saveButton().assertIsNotEnabled()
    }

    @Test
    fun `the sign-in form needs the secret again even though one is stored`() {
        setSheetContent(ExplorerDialogState.SftpLocationForm(mode = SftpFormMode.SIGN_IN, existing = stored))

        composeTestRule.onNodeWithText("Sign in to Build server").assertIsDisplayed()
        saveButton().assertIsNotEnabled()

        field("Password").performTextInput("new-secret")
        saveButton().assertIsEnabled()
    }

    @Test
    fun `submitting hands over the fields and how often they were edited`() {
        setSheetContent(ExplorerDialogState.SftpLocationForm())

        field("Server address").performTextInput("build.lan")
        field("Username").performTextInput("darken")
        field("Password").performTextInput("hunter2")
        saveButton().performClick()

        val first = submitted!!
        first.host shouldBe "build.lan"
        first.port shouldBe "22"
        first.username shouldBe "darken"
        first.password shouldBe "hunter2"
        first.authType shouldBe SftpLocation.AuthType.PASSWORD

        field("build.lan").performTextReplacement("other.lan")
        saveButton().performClick()
        submitted!!.revision shouldBeGreaterThan first.revision
    }

    @Test
    fun `saving is blocked while testing and an error is shown inline`() {
        setSheetContent(
            ExplorerDialogState.SftpLocationForm(
                mode = SftpFormMode.EDIT,
                existing = stored,
                isTesting = true,
                error = "build.lan rejected the username, password or key".toCaString(),
            )
        )

        composeTestRule.onNodeWithText("Connecting…").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("build.lan rejected the username, password or key").performScrollTo()
            .assertIsDisplayed()
        saveButton().assertIsNotEnabled()
    }

    @Test
    fun `nothing in the form can be changed while a connection test runs`() {
        setSheetContent(ExplorerDialogState.SftpLocationForm(isTesting = true))

        field("Server address").assertIsNotEnabled()
        field("Username").assertIsNotEnabled()
        // The "Password" auth chip; the password field with the same label is the one with editable text
        composeTestRule.onNode(hasText("Password") and !isTextField).performScrollTo().assertIsNotEnabled()
        // "Remember credentials" is the only toggleable node in the add form
        composeTestRule.onNode(isToggleable()).performScrollTo().assertIsNotEnabled()
    }
}
