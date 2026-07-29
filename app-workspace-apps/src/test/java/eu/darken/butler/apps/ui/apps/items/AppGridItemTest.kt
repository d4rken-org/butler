package eu.darken.butler.apps.ui.apps.items

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import org.junit.Test
import testhelpers.ComposeTest

class AppGridItemTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

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

    @Test
    fun `tile shows the size chip when a size is known`() {
        val bytes = AppsMockDataProvider.MockSizes.mb(128)
        composeTestRule.setContent {
            PreviewWrapper {
                AppGridItem(
                    item = AppsMockDataProvider.createMockAppItem(
                        packageName = "com.android.chrome",
                        label = "Chrome",
                        appSize = bytes,
                    ),
                    isSelected = false,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(formatFileSize(context, bytes)).assertIsDisplayed()
    }

    @Test
    fun `tile shows no size chip while the size is unknown`() {
        val bytes = AppsMockDataProvider.MockSizes.mb(128)
        composeTestRule.setContent {
            PreviewWrapper {
                AppGridItem(
                    item = AppsMockDataProvider.createMockAppItem(
                        packageName = "com.android.chrome",
                        label = "Chrome",
                        appSize = null,
                    ),
                    isSelected = false,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(formatFileSize(context, bytes)).assertDoesNotExist()
    }
}
