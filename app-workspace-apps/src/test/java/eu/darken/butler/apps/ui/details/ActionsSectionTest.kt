package eu.darken.butler.apps.ui.details

import android.content.pm.PackageInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.pkgs.container.UninstalledPkg
import eu.darken.butler.common.user.UserHandle2
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * An app uninstalled for this user has no install to command: no launch intent, no APK to export,
 * and every package command would target something that is not there.
 */
class ActionsSectionTest : ComposeTest() {

    private val uninstalled = AppInfo(
        install = UninstalledPkg(
            packageInfo = PackageInfo().apply { packageName = PKG },
            userHandle = UserHandle2(0),
        ),
    )

    private fun setContent(app: AppInfo) {
        composeTestRule.setContent {
            PreviewWrapper {
                ActionsSection(
                    app = app,
                    onLaunchApp = {},
                    onShowAppInfo = {},
                    onEnableDisable = {},
                    onUninstall = {},
                    onExportApk = {},
                    onShareApk = {},
                    onForceStop = {},
                    onClearData = {},
                    isUninstalled = app.isUninstalled,
                )
            }
        }
    }

    @Test
    fun `an uninstalled package is recognized as one`() {
        uninstalled.isUninstalled shouldBe true
        AppsMockDataProvider.Presets.chrome.isUninstalled shouldBe false
    }

    @Test
    fun `an uninstalled app offers app info and share only`() {
        setContent(uninstalled)

        composeTestRule.onNodeWithText("App info").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share info").assertIsDisplayed()

        listOf("Launch", "Disable", "Enable", "Force stop", "Clear data", "Uninstall", "Export APK").forEach {
            composeTestRule.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun `an installed app keeps every action`() {
        setContent(AppsMockDataProvider.Presets.chrome)

        listOf("Launch", "App info", "Force stop", "Clear data", "Uninstall", "Export APK", "Share info").forEach {
            composeTestRule.onNodeWithText(it).assertExists()
        }
    }

    companion object {
        private const val PKG = "com.example.app"
    }
}
