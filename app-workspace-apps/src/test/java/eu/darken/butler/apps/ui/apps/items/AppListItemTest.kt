package eu.darken.butler.apps.ui.apps.items

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.R
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

        // By tag, not by an expected size string: asserting a size that was never supplied is absent
        // would also pass if the unknown branch regressed to rendering a chip reading "0 B".
        composeTestRule.onNodeWithTag(APP_SIZE_CHIP_TAG, useUnmergedTree = true).assertDoesNotExist()
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

        // Adjacency, not mere presence: both have to hang off the same row node, so moving the chip
        // back onto a line of its own fails here. The tag label, not the app label "Phone" - that
        // one lives in the item's title and would match no matter where the chip sat.
        val row = composeTestRule.onNodeWithTag(APP_SIZE_TAG_ROW_TAG, useUnmergedTree = true)
        row.assertExists()
        row.assert(hasAnyDescendant(hasText(formatFileSize(context, bytes))))
        row.assert(hasAnyDescendant(hasText(context.getString(R.string.apps_tag_system_label))))
    }
}
