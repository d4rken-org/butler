package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication

@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class NetworkProtocolChooserDialogTest : ComposeTest() {

    @Test
    fun `the protocol chooser offers WebDAV`() {
        var chosen: NetworkProtocol? = null
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    NetworkProtocolChooserDialog(onChoose = { chosen = it }, onDismiss = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Nextcloud, ownCloud and other WebDAV servers").assertIsDisplayed()
        composeTestRule.onNodeWithText("WebDAV").performClick()
        chosen shouldBe NetworkProtocol.WEBDAV
    }
}
