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

class AppListItemTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `row shows the size chip when a size is known`() {
        val bytes = AppsMockDataProvider.MockSizes.mb(128)
        composeTestRule.setContent {
            PreviewWrapper {
                AppListItem(
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

        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText(formatFileSize(context, bytes)).assertIsDisplayed()
    }

    @Test
    fun `row shows no size chip while the size is unknown`() {
        val bytes = AppsMockDataProvider.MockSizes.mb(128)
        composeTestRule.setContent {
            PreviewWrapper {
                AppListItem(
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

    @Test
    fun `size chip and tags share the same row`() {
        val bytes = AppsMockDataProvider.MockSizes.gb(1)
        composeTestRule.setContent {
            PreviewWrapper {
                AppListItem(
                    item = AppsMockDataProvider.Presets.multiTagAppItem.copy(appSize = bytes),
                    isSelected = false,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(formatFileSize(context, bytes)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Phone").assertIsDisplayed()
    }
}
