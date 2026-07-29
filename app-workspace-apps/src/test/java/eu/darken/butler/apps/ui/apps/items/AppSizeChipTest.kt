package eu.darken.butler.apps.ui.apps.items

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.R
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import org.junit.Test
import testhelpers.ComposeTest

class AppSizeChipTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `chip shows the formatted size`() {
        val bytes = AppsMockDataProvider.MockSizes.mb(128)
        composeTestRule.setContent {
            PreviewWrapper {
                AppSizeChip(bytes = bytes)
            }
        }

        composeTestRule.onNodeWithText(formatFileSize(context, bytes)).assertIsDisplayed()
    }

    @Test
    fun `chip carries a content description`() {
        val bytes = AppsMockDataProvider.MockSizes.gb(3)
        composeTestRule.setContent {
            PreviewWrapper {
                AppSizeChip(bytes = bytes, compact = true)
            }
        }

        val expected = context.getString(R.string.apps_size_chip_desc, formatFileSize(context, bytes))
        composeTestRule.onNodeWithContentDescription(expected).assertExists()
    }
}
