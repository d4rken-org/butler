package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SftpUnlockDialogTest : ComposeTest() {

    private val location = SftpLocation(
        id = Uuid.parse("66666666-7777-8888-9999-000000000000"),
        label = "Build server",
        host = "build.lan",
        username = "darken",
        basePath = "/srv",
        authType = SftpLocation.AuthType.PRIVATE_KEY,
        rememberCredential = true,
        rememberPassphrase = false,
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

    private val unlocked = mutableListOf<String>()
    private var editRequests = 0
    private var dismissals = 0

    private fun setDialogContent(state: ExplorerDialogState.SftpUnlock = ExplorerDialogState.SftpUnlock(location)) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SftpUnlockDialog(
                        state = state,
                        onUnlock = { unlocked.add(it) },
                        onEditServer = { editRequests++ },
                        onDismiss = { dismissals++ },
                    )
                }
            }
        }
    }

    private fun field() = composeTestRule.onNode(hasSetTextAction())

    private val masked = SemanticsMatcher.expectValue(
        SemanticsProperties.EditableText,
        AnnotatedString("•".repeat("hunter2".length)),
    )

    private fun button(label: String) = composeTestRule.onNode(hasClickAction() and hasText(label))

    @Test
    fun `shows the server name and asks for the passphrase`() {
        setDialogContent()

        composeTestRule.onNode(hasText("Unlock Build server")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Enter the key's passphrase.")).assertIsDisplayed()
    }

    @Test
    fun `unlock is disabled until a passphrase is typed`() {
        setDialogContent()
        button("Unlock").assertIsNotEnabled()

        field().performTextInput("hunter2")
        button("Unlock").assertIsEnabled()
        button("Unlock").performClick()

        composeTestRule.runOnIdle { unlocked shouldBe listOf("hunter2") }
    }

    @Test
    fun `the keyboard action unlocks`() {
        setDialogContent()

        field().performTextInput("hunter2")
        field().performImeAction()

        composeTestRule.runOnIdle { unlocked shouldBe listOf("hunter2") }
    }

    @Test
    fun `an empty passphrase is not submitted from the keyboard`() {
        setDialogContent()

        field().performImeAction()

        composeTestRule.runOnIdle { unlocked shouldBe emptyList() }
    }

    @Test
    fun `the passphrase is hidden until revealed`() {
        setDialogContent()
        field().performTextInput("hunter2")

        field().assert(masked).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

        composeTestRule.onNode(hasContentDescription("Show password")).performClick()

        field()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("hunter2")))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        composeTestRule.onNode(hasContentDescription("Hide password")).performClick()
        field().assert(masked).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun `edit server and cancel report back`() {
        setDialogContent()

        button("Edit server").performClick()
        button("Cancel").performClick()

        composeTestRule.runOnIdle {
            editRequests shouldBe 1
            dismissals shouldBe 1
            unlocked shouldBe emptyList()
        }
    }

    @Test
    fun `an error is shown under the field`() {
        setDialogContent(
            ExplorerDialogState.SftpUnlock(
                location = location,
                error = "The passphrase does not unlock this private key".toCaString(),
            ),
        )

        composeTestRule.onNode(hasText("The passphrase does not unlock this private key")).assertIsDisplayed()
    }

    @Test
    fun `nothing can be pressed while unlocking`() {
        setDialogContent(ExplorerDialogState.SftpUnlock(location = location, isUnlocking = true))

        composeTestRule.onNode(hasText("Connecting…")).assertIsDisplayed()
        button("Unlock").assertIsNotEnabled()
        button("Cancel").assertIsNotEnabled()
        button("Edit server").assertIsNotEnabled()
    }
}
