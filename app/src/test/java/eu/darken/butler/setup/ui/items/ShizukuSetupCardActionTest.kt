package eu.darken.butler.setup.ui.items

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import testhelpers.ComposeTest

/**
 * Whenever ADB access is enabled, the card has to offer a route to the manager app: naming a state
 * and stopping there is what left a stranded user with nothing to act on.
 */
class ShizukuSetupCardActionTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun installManager() {
        val info = PackageInfo().apply {
            packageName = MANAGER_PKG
            applicationInfo = ApplicationInfo().apply {
                packageName = MANAGER_PKG
                nonLocalizedLabel = MANAGER_LABEL
            }
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun render(
        pkg: String,
        isInstalled: Boolean,
        serviceState: ShizukuServiceState = ShizukuServiceState.NotChecked,
        restartRequiredFor: String? = null,
        restartRequiredLabel: String? = null,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                RootShizukuActions(
                    item = SetupItem(
                        type = SetupModule.Type.SHIZUKU,
                        state = ShizukuSetupModule.Result(
                            pkg = pkg.toPkgId(),
                            useShizuku = true,
                            isCompatible = true,
                            isInstalled = isInstalled,
                            restartRequiredFor = restartRequiredFor?.toPkgId(),
                            restartRequiredLabel = restartRequiredLabel,
                            serviceState = serviceState,
                        ),
                        isRequired = false,
                        priority = 6,
                    ),
                    onExecuteAction = {},
                    switchLabel = context.getString(R.string.setup_use_shizuku_label),
                )
            }
        }
    }

    @Test
    fun `an installed manager is offered by name`() {
        installManager()

        render(pkg = MANAGER_PKG, isInstalled = true)

        composeTestRule
            .onNode(hasClickAction() and hasText(MANAGER_LABEL, substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `without a manager the card offers to install one`() {
        render(pkg = DEFAULT_MANAGER_PKG, isInstalled = false)

        composeTestRule
            .onNode(hasClickAction() and hasText(INSTALL_WORDING, substring = true, ignoreCase = true))
            .assertIsDisplayed()
    }

    @Test
    fun `a connected card keeps the manager within reach`() {
        installManager()

        render(pkg = MANAGER_PKG, isInstalled = true, serviceState = ShizukuServiceState.Available)

        composeTestRule
            .onNode(hasClickAction() and hasText(MANAGER_LABEL, substring = true))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodes(hasClickAction() and hasText(INSTALL_WORDING, substring = true, ignoreCase = true))
            .assertCountEquals(0)
    }

    /** Two managers can be installed at once, so "Connected" on its own does not say which one won. */
    @Test
    fun `a connected card names the manager it bound to`() {
        installManager()

        render(pkg = MANAGER_PKG, isInstalled = true, serviceState = ShizukuServiceState.Available)

        composeTestRule
            .onNodeWithText(context.getString(R.string.setup_adb_status_connected_via, MANAGER_LABEL))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.setup_status_connected))
            .assertCountEquals(0)
    }

    /** A package id is not a name: an unreadable label falls back to the backend's product name. */
    @Test
    fun `an unreadable label falls back to the backend name, never the package id`() {
        render(pkg = DEFAULT_MANAGER_PKG, isInstalled = true)

        composeTestRule
            .onNode(hasClickAction() and hasText(AdbBackend.SHIZUKU.label, substring = true))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodes(hasText(DEFAULT_MANAGER_PKG, substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun `a manager for the other backend offers no install action`() {
        installManager()

        render(
            pkg = DEFAULT_MANAGER_PKG,
            isInstalled = false,
            restartRequiredFor = MANAGER_PKG,
            restartRequiredLabel = MANAGER_LABEL,
        )

        composeTestRule
            .onAllNodes(hasClickAction() and hasText(INSTALL_WORDING, substring = true, ignoreCase = true))
            .assertCountEquals(0)
        composeTestRule
            .onNode(hasText(context.getString(R.string.setup_adb_restart_required, MANAGER_LABEL)))
            .assertIsDisplayed()
    }

    companion object {
        private const val MANAGER_PKG = "moe.shizuku.privileged.api"
        private const val MANAGER_LABEL = "Shizuku"
        private const val DEFAULT_MANAGER_PKG = "eu.darken.porter"

        private const val INSTALL_WORDING = "install"
    }
}
