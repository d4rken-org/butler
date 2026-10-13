package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.ui.explorer.elements.WebDavCertificateCard
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.uuid.Uuid

@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class WebDavCertificateDialogsTest : ComposeTest() {

    private val stored = MockDataProvider.mockWebDavCertificate(1)
    private val presented = MockDataProvider.mockWebDavCertificate(2)
    private val url = "https://cloud.example.com/remote.php/dav/files/alice/"
    private val endpoint = "alice@cloud.example.com/remote.php/dav/files/alice"

    private fun retrust(stored: Boolean) = WebDavRetrustConfirmation(
        locationId = Uuid.parse("88888888-9999-aaaa-bbbb-cccccccccccc"),
        url = url,
        endpoint = endpoint,
        stored = this.stored.takeIf { stored },
        presented = presented,
        trustRevision = 1,
    )

    @Test
    fun `an untrusted certificate is shown with its names, validity and fingerprint`() {
        var accepted = 0
        var cancelled = 0
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    WebDavCertificateConfirmationDialog(
                        confirmation = WebDavCertificateConfirmation(url = url, presented = presented, formRevision = 3),
                        onAccept = { accepted++ },
                        onCancel = { cancelled++ },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(presented.sha256Fingerprint).assertIsDisplayed()
        composeTestRule.onNodeWithText("Issued to").assertIsDisplayed()
        composeTestRule.onNodeWithText("Issued by").assertIsDisplayed()
        composeTestRule.onNodeWithText("Valid").assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(presented.subject)).fetchSemanticsNodes().size shouldBe 2
        composeTestRule.onNode(hasText(url, substring = true)).assertIsDisplayed()

        composeTestRule.onNodeWithText("Cancel").performClick()
        cancelled shouldBe 1
        composeTestRule.onNodeWithText("Trust certificate").performClick()
        accepted shouldBe 1
    }

    @Test
    fun `a re-trust shows the trusted and the presented fingerprint`() {
        var accepted = 0
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    WebDavCertificateRetrustDialog(
                        confirmation = retrust(stored = true),
                        onAccept = { accepted++ },
                        onCancel = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Replace the trusted certificate?").assertIsDisplayed()
        composeTestRule.onNodeWithText(stored.sha256Fingerprint).assertIsDisplayed()
        composeTestRule.onNodeWithText(presented.sha256Fingerprint).assertIsDisplayed()
        composeTestRule.onNodeWithText("Replace certificate").performClick()
        accepted shouldBe 1
    }

    @Test
    fun `a re-trust with nothing pinned asks to trust the presented certificate`() {
        var accepted = 0
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    WebDavCertificateRetrustDialog(
                        confirmation = retrust(stored = false),
                        onAccept = { accepted++ },
                        onCancel = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Trust this certificate?").assertIsDisplayed()
        composeTestRule.onNodeWithText(presented.sha256Fingerprint).assertIsDisplayed()
        composeTestRule.onNodeWithText(stored.sha256Fingerprint).assertDoesNotExist()
        composeTestRule.onNodeWithText("Trust certificate").performClick()
        accepted shouldBe 1
    }

    @Test
    fun `the card for an untrusted certificate shows its fingerprint and offers a review`() {
        var reviews = 0
        composeTestRule.setContent {
            PreviewWrapper {
                WebDavCertificateCard(
                    endpoint = endpoint,
                    presented = presented,
                    stored = null,
                    onReview = { reviews++ },
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Certificate not trusted").assertIsDisplayed()
        composeTestRule.onNodeWithText(presented.sha256Fingerprint).assertIsDisplayed()
        composeTestRule.onNode(hasText(endpoint, substring = true)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Review").performClick()
        reviews shouldBe 1
    }

    @Test
    fun `the card for a changed certificate shows both fingerprints and offers a review`() {
        var reviews = 0
        composeTestRule.setContent {
            PreviewWrapper {
                WebDavCertificateCard(
                    endpoint = endpoint,
                    presented = presented,
                    stored = stored,
                    onReview = { reviews++ },
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Server certificate changed").assertIsDisplayed()
        composeTestRule.onNodeWithText(stored.sha256Fingerprint).assertIsDisplayed()
        composeTestRule.onNodeWithText(presented.sha256Fingerprint).assertIsDisplayed()
        composeTestRule.onNodeWithText("Review").performClick()
        reviews shouldBe 1
    }
}
