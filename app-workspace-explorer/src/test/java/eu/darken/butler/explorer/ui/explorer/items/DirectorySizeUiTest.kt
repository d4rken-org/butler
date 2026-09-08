package eu.darken.butler.explorer.ui.explorer.items

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.core.sizes.DirectorySize
import org.junit.Test
import testhelpers.ComposeTest

class DirectorySizeUiTest : ComposeTest() {
    @Test
    fun `estimated partial sizes never claim to be a measured lower bound`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val formatted = formatFileSize(context, 12000)
        composeTestRule.setContent {
            PreviewWrapper {
                Column {
                    Text(directorySizeLabel(DirectorySize(12000, true)))
                    Text(directorySizeLabel(DirectorySize(12000, false)))
                    Text(directorySizeLabel(DirectorySize(12000, false, 10000, false)))
                    Text(directorySizeLabel(DirectorySize(12000, false, 10000, true)))
                }
            }
        }
        composeTestRule.onNodeWithText(formatted).assertExists()
        composeTestRule.onNodeWithText("≥ $formatted").assertExists()
        composeTestRule.onNodeWithText("≈ $formatted").assertExists()
        composeTestRule.onNodeWithText("≈ $formatted · partial").assertExists()
    }
}
