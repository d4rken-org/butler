package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ExplorerInfoBarNetworkChipTest : ComposeTest() {

    @Test
    fun `the network chip selects every location`() {
        var selectedAll = false
        composeTestRule.setContent {
            PreviewWrapper {
                ExplorerInfoBar(
                    info = ExplorerLocation.Network.Info(locationCount = 2),
                    onSelectAll = { selectedAll = true },
                )
            }
        }

        composeTestRule.onNodeWithText("2 network locations").assertHasClickAction().performClick()
        selectedAll shouldBe true
    }

    @Test
    fun `the network chip is inert in a single-select picker`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ExplorerInfoBar(
                    info = ExplorerLocation.Network.Info(locationCount = 2),
                    onSelectAll = {},
                    canSelectMultiple = false,
                )
            }
        }

        composeTestRule.onNodeWithText("2 network locations").assertHasNoClickAction()
    }
}
