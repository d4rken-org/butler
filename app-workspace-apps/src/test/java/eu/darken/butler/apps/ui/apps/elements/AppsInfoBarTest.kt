package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class AppsInfoBarTest : ComposeTest() {

    @Test
    fun `user apps chip selects user apps`() {
        var userClicks = 0
        var systemClicks = 0
        composeTestRule.setContent {
            PreviewWrapper {
                AppsInfoBar(
                    userAppsCount = 25,
                    systemAppsCount = 142,
                    onSelectUserApps = { userClicks++ },
                    onSelectSystemApps = { systemClicks++ },
                )
            }
        }

        composeTestRule.onNodeWithText("25 user apps").performClick()

        userClicks shouldBe 1
        systemClicks shouldBe 0
    }

    @Test
    fun `system apps chip selects system apps`() {
        var userClicks = 0
        var systemClicks = 0
        composeTestRule.setContent {
            PreviewWrapper {
                AppsInfoBar(
                    userAppsCount = 25,
                    systemAppsCount = 142,
                    onSelectUserApps = { userClicks++ },
                    onSelectSystemApps = { systemClicks++ },
                )
            }
        }

        composeTestRule.onNodeWithText("142 system apps").performClick()

        systemClicks shouldBe 1
        userClicks shouldBe 0
    }

    @Test
    fun `chips render without selection callbacks`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppsInfoBar(
                    userAppsCount = 25,
                    systemAppsCount = 142,
                )
            }
        }

        composeTestRule.onNodeWithText("25 user apps").assertIsDisplayed()
        composeTestRule.onNodeWithText("142 system apps").assertIsDisplayed()
    }

    @Test
    fun `count chips are hidden while a selection is active`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppsInfoBar(
                    userAppsCount = 25,
                    systemAppsCount = 142,
                    selectedCount = 3,
                    onSelectUserApps = {},
                    onSelectSystemApps = {},
                )
            }
        }

        composeTestRule.onNodeWithText("25 user apps").assertDoesNotExist()
        composeTestRule.onNodeWithText("142 system apps").assertDoesNotExist()
    }
}
