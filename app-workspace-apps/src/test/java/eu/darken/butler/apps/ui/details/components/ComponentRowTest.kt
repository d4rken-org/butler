package eu.darken.butler.apps.ui.details.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ComponentRowTest : ComposeTest() {

    private fun entry(
        isExported: Boolean = false,
        enabledState: ComponentEnabledState = ComponentEnabledState.UNRESOLVED,
    ) = ComponentEntry(
        kind = ComponentKind.ACTIVITY,
        packageName = "com.example.app",
        className = "com.example.app.MainActivity",
        isExported = isExported,
        enabledState = enabledState,
    )

    @Test
    fun `an exported component shows the exported chip`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ComponentRow(entry = entry(isExported = true), query = "", onClick = {})
            }
        }

        composeTestRule.onNodeWithText("Exported").assertIsDisplayed()
        composeTestRule.onNodeWithText("Disabled").assertDoesNotExist()
    }

    @Test
    fun `a disabled component shows the disabled chip`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ComponentRow(
                    entry = entry(enabledState = ComponentEnabledState.DISABLED),
                    query = "",
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Disabled").assertIsDisplayed()
    }

    @Test
    fun `an enabled component shows no state chip`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ComponentRow(
                    entry = entry(enabledState = ComponentEnabledState.ENABLED),
                    query = "",
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Disabled").assertDoesNotExist()
        composeTestRule.onNodeWithText("Exported").assertDoesNotExist()
    }

    @Test
    fun `an unresolved component shows no state chip`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ComponentRow(entry = entry(), query = "", onClick = {})
            }
        }

        composeTestRule.onNodeWithText("Disabled").assertDoesNotExist()
    }

    @Test
    fun `the whole row is clickable and there is no inline launch affordance`() {
        var clicks = 0
        composeTestRule.setContent {
            PreviewWrapper {
                ComponentRow(entry = entry(isExported = true), query = "", onClick = { clicks++ })
            }
        }

        composeTestRule.onNodeWithContentDescription("Launch").assertDoesNotExist()
        composeTestRule.onNodeWithText("MainActivity").performClick()

        clicks shouldBe 1
    }
}
