package eu.darken.butler.setup.core.shizuku

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.ui.items.LocalAdbManagerInstallGuide
import eu.darken.butler.setup.ui.items.RootShizukuActions
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class AdbManagerInstallGuideGplayTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the Play build recommends Shizuku from Google Play`() {
        val guide = AdbManagerInstallGuideModule.guide()

        guide.backend shouldBe AdbBackend.SHIZUKU
        guide.url shouldBe "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
    }

    @Test
    fun `the install action names Shizuku`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalAdbManagerInstallGuide provides AdbManagerInstallGuideModule.guide()) {
                    RootShizukuActions(
                        item = SetupItem(
                            type = SetupModule.Type.SHIZUKU,
                            state = ShizukuSetupModule.Result(useShizuku = true, isInstalled = false),
                            isRequired = false,
                            priority = 6,
                        ),
                        onExecuteAction = {},
                        switchLabel = context.getString(R.string.setup_use_shizuku_label),
                    )
                }
            }
        }

        composeTestRule
            .onNode(hasClickAction() and hasText(context.getString(R.string.setup_adb_install_manager_action, "Shizuku")))
            .assertIsDisplayed()
    }
}
