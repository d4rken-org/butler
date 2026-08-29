package eu.darken.butler.workspace.ui.manager

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/** The size class has to follow the window, not the size the caller was first composed at. */
class WindowSizeInfoTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sizedConfiguration(widthDp: Int, heightDp: Int) =
        Configuration(context.resources.configuration).apply {
            screenWidthDp = widthDp
            screenHeightDp = heightDp
        }

    @Test
    fun `the size class follows a window resize`() {
        var configuration by mutableStateOf(sizedConfiguration(widthDp = 400, heightDp = 800))
        lateinit var sizeInfo: WindowSizeInfo

        composeTestRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                sizeInfo = rememberWindowSizeInfo()
            }
        }
        composeTestRule.waitForIdle()

        sizeInfo.widthSizeClass shouldBe WindowSizeInfo.SizeClass.COMPACT

        composeTestRule.runOnIdle { configuration = sizedConfiguration(widthDp = 1000, heightDp = 800) }
        composeTestRule.waitForIdle()

        sizeInfo.widthDp shouldBe 1000.dp
        sizeInfo.widthSizeClass shouldBe WindowSizeInfo.SizeClass.EXPANDED
    }
}
