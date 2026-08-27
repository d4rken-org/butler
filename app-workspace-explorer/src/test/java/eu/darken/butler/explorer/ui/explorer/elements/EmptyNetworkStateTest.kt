package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class EmptyNetworkStateTest : ComposeTest() {

    @Test
    fun `the add button reports the tap`() {
        var added = false
        composeTestRule.setContent {
            PreviewWrapper {
                EmptyNetworkState(onAddLocation = { added = true }, initiallyVisible = true)
            }
        }

        composeTestRule.onNodeWithText("No network storage yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add network storage").performClick()

        added shouldBe true
    }

    /** A picker cannot administer locations, so it must not offer a button that bypasses that. */
    @Test
    fun `the add button is gone when adding is not allowed`() {
        composeTestRule.setContent {
            PreviewWrapper {
                EmptyNetworkState(onAddLocation = {}, showAddAction = false, initiallyVisible = true)
            }
        }

        composeTestRule.onNodeWithText("No network storage yet").assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("Add network storage")).fetchSemanticsNodes().size shouldBe 0
    }
}
