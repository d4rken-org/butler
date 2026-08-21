package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerExternalChange
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import eu.darken.butler.common.R as CommonR

class ViewerExternalChangeBannerTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun setContent(change: ViewerExternalChange, onRefresh: () -> Unit = {}) {
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerExternalChangeBanner(change = change, onRefresh = onRefresh)
            }
        }
    }

    @Test
    fun `a modified file says the file changed`() {
        setContent(ViewerExternalChange.Modified)

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_external_change_title))
            .assertIsDisplayed()
    }

    @Test
    fun `a deleted file says the file is gone`() {
        // Different wording, same banner: "changed, refresh to load it again" would be a lie here.
        setContent(ViewerExternalChange.Gone)

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_external_change_gone_title))
            .assertIsDisplayed()
    }

    @Test
    fun `refresh reports back`() {
        var refreshed = 0
        setContent(ViewerExternalChange.Modified, onRefresh = { refreshed++ })

        composeTestRule
            .onNodeWithText(context.getString(CommonR.string.general_refresh_action))
            .performClick()

        refreshed shouldBe 1
    }
}
