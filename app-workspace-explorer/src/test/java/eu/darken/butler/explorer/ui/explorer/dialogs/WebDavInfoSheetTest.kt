package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication

@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class WebDavInfoSheetTest : ComposeTest() {

    private fun show(item: ExplorerItem.Storage.Network) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    ItemInfoBottomSheet(
                        context = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(item.location.id, item),
                        onDismiss = {},
                        onCopyToClipboard = {},
                    )
                }
            }
        }
    }

    private fun shown(text: String) = composeTestRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `a signed-in server with a pinned certificate shows its URL, account and fingerprint`() {
        val pin = MockDataProvider.mockTrustedCertificate(1)
        show(MockDataProvider.createMockStorageWebDav(pinnedCertificate = pin))

        shown("WebDAV")
        shown("https://cloud.example.com/remote.php/dav/files/alice/")
        shown("Username and password")
        shown("alice")
        shown("Stored on this device")
        shown("Trusted certificate (SHA-256)")
        shown(pin.fingerprint)
    }

    @Test
    fun `a server the system trusts says so`() {
        show(MockDataProvider.createMockStorageWebDav())

        shown("Certificate")
        shown("Trusted by the system")
    }

    @Test
    fun `an anonymous HTTP server has no password and is not encrypted`() {
        show(
            MockDataProvider.createMockStorageWebDav(
                url = "http://nas.local:8080/dav/",
                authType = WebDavLocation.AuthType.ANONYMOUS,
            )
        )

        shown("Anonymous")
        shown("Not encrypted (HTTP)")
        composeTestRule.onNodeWithText("Password").assertDoesNotExist()
    }

    @Test
    fun `a missing password asks for a sign-in`() {
        show(MockDataProvider.createMockStorageWebDav(status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED))

        shown("Missing, sign in again")
    }
}
