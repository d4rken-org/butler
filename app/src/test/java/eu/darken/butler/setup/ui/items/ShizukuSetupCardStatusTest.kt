package eu.darken.butler.setup.ui.items

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.adb.shizuku.AdbPermissionState
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.shizuku.AdbManagerInstallGuide
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule
import org.junit.Test
import testhelpers.ComposeTest

/** Which line the ADB access card shows under Optional/Configured, for every state it can be in. */
class ShizukuSetupCardStatusTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val guide = object : AdbManagerInstallGuide {
        override val backend: AdbBackend = AdbBackend.PORTER
        override val url: String = "https://porter.darken.eu/setup"
    }

    private fun render(result: ShizukuSetupModule.Result) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalAdbManagerInstallGuide provides guide) {
                    RootShizukuActions(
                        item = SetupItem(
                            type = SetupModule.Type.SHIZUKU,
                            state = result,
                            isRequired = false,
                            priority = 6,
                        ),
                        onExecuteAction = {},
                        switchLabel = context.getString(R.string.setup_use_shizuku_label),
                    )
                }
            }
        }
    }

    private fun assertStatus(resId: Int, vararg args: Any) {
        composeTestRule.onNodeWithText(context.getString(resId, *args)).assertIsDisplayed()
    }

    private val allStatusLines by lazy {
        listOf(
            context.getString(R.string.setup_status_not_installed),
            context.getString(R.string.setup_adb_status_butler_update_required),
            context.getString(R.string.setup_adb_status_manager_update_required),
            context.getString(R.string.setup_status_connected),
            context.getString(R.string.setup_status_permission_denied),
            context.getString(R.string.setup_status_connection_failed),
            context.getString(R.string.setup_status_connecting),
            context.getString(R.string.setup_status_not_connected),
        )
    }

    @Test
    fun `a switch that was never touched shows no status line`() {
        render(CONNECTED.copy(useShizuku = null))

        allStatusLines.forEach { composeTestRule.onAllNodesWithText(it).assertCountEquals(0) }
        composeTestRule.onAllNodes(hasText("Connected via", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a switched off card shows no status line`() {
        render(CONNECTED.copy(useShizuku = false))

        allStatusLines.forEach { composeTestRule.onAllNodesWithText(it).assertCountEquals(0) }
        composeTestRule.onAllNodes(hasText("Connected via", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `nothing installed`() {
        render(ShizukuSetupModule.Result(useShizuku = true, isInstalled = false))

        assertStatus(R.string.setup_status_not_installed)
    }

    @Test
    fun `a client too old for the server asks for a Butler update`() {
        render(NOT_CONNECTED.copy(isCompatible = false, clientTooOld = true))

        assertStatus(R.string.setup_adb_status_butler_update_required)
    }

    @Test
    fun `a server too old for Butler asks for a manager update`() {
        render(NOT_CONNECTED.copy(isCompatible = false, serverTooOld = true))

        assertStatus(R.string.setup_adb_status_manager_update_required)
    }

    @Test
    fun `a connected card names the manager it goes through`() {
        render(CONNECTED)

        assertStatus(R.string.setup_adb_status_connected_via, "Porter")
        composeTestRule.onAllNodesWithText(context.getString(R.string.setup_status_connected)).assertCountEquals(0)
    }

    @Test
    fun `a connected card without a manager label names the backend`() {
        render(CONNECTED.copy(pkg = null, managerLabel = null))

        assertStatus(R.string.setup_adb_status_connected_via, AdbBackend.PORTER.label)
    }

    @Test
    fun `a denied permission`() {
        render(
            NOT_CONNECTED.copy(
                permissionState = AdbPermissionState.Denied(permanentlyDenied = false),
                basicService = true,
                serviceState = ShizukuServiceState.PermissionDenied,
            )
        )

        assertStatus(R.string.setup_status_permission_denied)
    }

    @Test
    fun `a permanently denied permission`() {
        render(
            NOT_CONNECTED.copy(
                permissionState = AdbPermissionState.Denied(permanentlyDenied = true),
                basicService = true,
            )
        )

        assertStatus(R.string.setup_status_permission_denied)
    }

    @Test
    fun `our service failing for good`() {
        render(NOT_CONNECTED.copy(basicService = true, serviceState = ShizukuServiceState.TimedOut))

        assertStatus(R.string.setup_status_connection_failed)
    }

    @Test
    fun `a connection whose service is not up yet`() {
        render(NOT_CONNECTED.copy(basicService = true, serviceState = ShizukuServiceState.Unknown))

        assertStatus(R.string.setup_status_connecting)
    }

    @Test
    fun `an installed manager without a connection`() {
        render(NOT_CONNECTED)

        assertStatus(R.string.setup_status_not_connected)
    }

    companion object {
        private val NOT_CONNECTED = ShizukuSetupModule.Result(
            useShizuku = true,
            backend = AdbBackend.PORTER,
            pkg = "eu.darken.porter".toPkgId(),
            managerLabel = "Porter",
            isInstalled = true,
        )
        private val CONNECTED = NOT_CONNECTED.copy(
            permissionState = AdbPermissionState.Granted,
            basicService = true,
            serviceState = ShizukuServiceState.Available,
        )
    }
}
