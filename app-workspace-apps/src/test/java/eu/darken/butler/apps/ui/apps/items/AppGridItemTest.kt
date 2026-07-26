package eu.darken.butler.apps.ui.apps.items

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import org.junit.Test
import testhelpers.ComposeTest

class AppGridItemTest : ComposeTest() {

    @Test
    fun `tile shows label and package name but not the version`() {
        val item = AppsMockDataProvider.createMockAppItem(
            packageName = "com.android.chrome",
            label = "Chrome",
            versionName = "120.0.6099",
        )
        composeTestRule.setContent {
            PreviewWrapper {
                AppGridItem(
                    item = item,
                    isSelected = false,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("com.android.chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("v120.0.6099").assertDoesNotExist()
    }

    @Test
    fun `package name row renders even without a version`() {
        val item = AppsMockDataProvider.createMockAppItem(
            packageName = "com.example.noversion",
            label = "No Version",
            versionName = null,
        )
        composeTestRule.setContent {
            PreviewWrapper {
                AppGridItem(
                    item = item,
                    isSelected = false,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("com.example.noversion").assertIsDisplayed()
    }

    @Test
    fun `disabled app still shows its label and package name`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppGridItem(
                    item = AppsMockDataProvider.Presets.disabledAppItem,
                    isSelected = false,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Spotify").assertIsDisplayed()
        composeTestRule.onNodeWithText("com.spotify.music").assertIsDisplayed()
    }
}
