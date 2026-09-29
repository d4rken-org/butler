package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.explorer.ui.explorer.elements.SftpHostKeyChangedCard
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.uuid.Uuid

@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class SftpHostKeyDialogsTest : ComposeTest() {

    private val stored = TrustedHostKey(
        type = "ssh-ed25519",
        blob = ByteArray(51),
        fingerprint = "SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM",
    )
    private val presented = TrustedHostKey(
        type = "ssh-rsa",
        blob = ByteArray(51) { 1 },
        fingerprint = "SHA256:m3r9Qk7fN2hYbWc8vXzL1aPpT6uE0sJdG4iKoRtVyHQ",
    )

    @Test
    fun `an unknown key is shown with type, fingerprint and endpoint`() {
        var accepted = 0
        var cancelled = 0
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SftpHostKeyConfirmationDialog(
                        confirmation = SftpHostKeyConfirmation(
                            host = "fe80::1",
                            port = 2222,
                            presentedKey = presented,
                            formRevision = 3,
                            keyGeneration = 0,
                        ),
                        onAccept = { accepted++ },
                        onCancel = { cancelled++ },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("ssh-rsa").assertIsDisplayed()
        composeTestRule.onNodeWithText(presented.fingerprint).assertIsDisplayed()
        composeTestRule.onNode(hasText("[fe80::1]:2222", substring = true)).assertIsDisplayed()

        composeTestRule.onNodeWithText("Cancel").performClick()
        cancelled shouldBe 1
        composeTestRule.onNodeWithText("Trust key").performClick()
        accepted shouldBe 1
    }

    @Test
    fun `a re-trust shows the confirmed and the presented key`() {
        var accepted = 0
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SftpHostKeyRetrustDialog(
                        confirmation = SftpRetrustConfirmation(
                            locationId = Uuid.parse("66666666-7777-8888-9999-000000000000"),
                            host = "build.lan",
                            port = 22,
                            endpoint = "darken@build.lan",
                            storedKey = stored,
                            presentedKey = presented,
                            trustRevision = 1,
                        ),
                        onAccept = { accepted++ },
                        onCancel = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("ssh-ed25519 ${stored.fingerprint}").assertIsDisplayed()
        composeTestRule.onNodeWithText("ssh-rsa ${presented.fingerprint}").assertIsDisplayed()
        composeTestRule.onNodeWithText("Replace key").performClick()
        accepted shouldBe 1
    }

    @Test
    fun `the changed-key card shows both keys and offers a review`() {
        var reviews = 0
        composeTestRule.setContent {
            PreviewWrapper {
                SftpHostKeyChangedCard(
                    endpoint = "darken@build.lan",
                    storedKey = stored,
                    presentedKey = presented,
                    onReview = { reviews++ },
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("ssh-ed25519 ${stored.fingerprint}").assertIsDisplayed()
        composeTestRule.onNodeWithText("ssh-rsa ${presented.fingerprint}").assertIsDisplayed()
        composeTestRule.onNodeWithText("Review").performClick()
        reviews shouldBe 1
    }
}
