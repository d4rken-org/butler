package eu.darken.butler.workspace.ui.modal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Menus render in their own popup window, outside the pane's pointer boundary, so the pane host
 * cannot swallow a press on their items. [DismissWhenPaneUnfocused] closes them instead the moment
 * their pane stops being the focused one.
 */
class DismissWhenPaneUnfocusedTest : ComposeTest() {

    @Test
    fun `an open menu is dismissed when its pane stops being focused`() {
        var paneFocused by mutableStateOf(true)
        var expanded by mutableStateOf(true)

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = paneFocused) {
                    Box {
                        DismissWhenPaneUnfocused(expanded = expanded) { expanded = false }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                modifier = Modifier.testTag(ITEM_TAG),
                                text = { Text("item") },
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(ITEM_TAG).assertExists()

        composeTestRule.runOnIdle { paneFocused = false }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(ITEM_TAG).assertDoesNotExist()
        expanded shouldBe false
    }

    /** Opening while the pane is already unfocused — keyboard or accessibility paths — also closes. */
    @Test
    fun `a menu opened in an unfocused pane is dismissed right away`() {
        var expanded by mutableStateOf(false)

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                    Box {
                        DismissWhenPaneUnfocused(expanded = expanded) { expanded = false }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { expanded = true }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { expanded shouldBe false }
    }

    /** Outside a pane [LocalPaneFocused] defaults to true — the helper must never trigger there. */
    @Test
    fun `a menu outside any pane is left alone`() {
        var expanded by mutableStateOf(true)

        composeTestRule.setContent {
            PreviewWrapper {
                Box {
                    DismissWhenPaneUnfocused(expanded = expanded) { expanded = false }
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { expanded shouldBe true }
    }

    companion object {
        private const val ITEM_TAG = "menu.item"
    }
}
