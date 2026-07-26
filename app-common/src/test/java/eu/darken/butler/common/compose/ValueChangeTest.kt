package eu.darken.butler.common.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The guard behind every scroll-to-top and bar-reset effect: firing on initial composition would
 * undo the position and collapse state a workspace has just restored, on every pane move, swipe and
 * rotation.
 */
class ValueChangeTest : ComposeTest() {

    @Test
    fun `does not fire on initial composition`() {
        val changes = mutableListOf<Pair<String, String>>()

        composeTestRule.setContent {
            OnValueChange("initial") { previous, current -> changes += previous to current }
        }
        composeTestRule.waitForIdle()

        changes shouldBe emptyList()
    }

    @Test
    fun `fires once per real change, with the previous value`() {
        val changes = mutableListOf<Pair<String, String>>()
        var value by mutableStateOf("a")

        composeTestRule.setContent {
            OnValueChange(value) { previous, current -> changes += previous to current }
        }
        composeTestRule.waitForIdle()

        value = "b"
        composeTestRule.waitForIdle()
        value = "c"
        composeTestRule.waitForIdle()

        changes shouldBe listOf("a" to "b", "b" to "c")
    }

    @Test
    fun `does not fire when recomposition keeps the value`() {
        val changes = mutableListOf<Pair<String, String>>()
        var unrelated by mutableStateOf(0)

        composeTestRule.setContent {
            // Read the unrelated state so the composable recomposes without its value changing
            unrelated.toString()
            OnValueChange("stable") { previous, current -> changes += previous to current }
        }
        composeTestRule.waitForIdle()

        unrelated = 1
        composeTestRule.waitForIdle()
        unrelated = 2
        composeTestRule.waitForIdle()

        changes shouldBe emptyList()
    }

    @Test
    fun `a value that returns to a previous one still counts as a change`() {
        val changes = mutableListOf<Pair<String, String>>()
        var value by mutableStateOf("a")

        composeTestRule.setContent {
            OnValueChange(value) { previous, current -> changes += previous to current }
        }
        composeTestRule.waitForIdle()

        value = "b"
        composeTestRule.waitForIdle()
        value = "a"
        composeTestRule.waitForIdle()

        changes shouldBe listOf("a" to "b", "b" to "a")
    }
}
