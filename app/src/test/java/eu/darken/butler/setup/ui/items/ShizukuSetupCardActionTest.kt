package eu.darken.butler.setup.ui.items

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.adb.shizuku.AdbPermissionState
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.shizuku.AdbManagerInstallGuide
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Whenever ADB access is enabled and there is something the user can do about it, the card offers a
 * route to the manager app: naming a state and stopping there left a stranded user with nothing to act on.
 */
class ShizukuSetupCardActionTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val actions = mutableListOf<SetupAction>()

    private fun guideFor(guideBackend: AdbBackend) = object : AdbManagerInstallGuide {
        override val backend: AdbBackend = guideBackend
        override val url: String = "https://example.com/install"
    }

    private fun render(
        result: ShizukuSetupModule.Result,
        guide: AdbManagerInstallGuide? = guideFor(AdbBackend.PORTER),
    ) {
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
                        onExecuteAction = { actions.add(it) },
                        switchLabel = context.getString(R.string.setup_use_shizuku_label),
                    )
                }
            }
        }
    }

    private fun openLabel(name: String) = context.getString(R.string.setup_adb_open_manager_action, name)

    private fun installLabel(name: String) = context.getString(R.string.setup_adb_install_manager_action, name)

    private val grantLabel get() = context.getString(R.string.setup_grant_access_label)

    private fun assertNoManagerAction() {
        composeTestRule.onAllNodes(hasText(openLabel(""), substring = true)).assertCountEquals(0)
        composeTestRule.onAllNodes(hasText(installLabel(""), substring = true)).assertCountEquals(0)
    }

    @Test
    fun `nothing installed offers the Porter install on a Porter guide`() {
        render(NOT_INSTALLED, guide = guideFor(AdbBackend.PORTER))

        composeTestRule.onNode(hasClickAction() and hasText(installLabel("Porter"))).assertIsDisplayed()
    }

    @Test
    fun `nothing installed offers the Shizuku install on a Shizuku guide`() {
        render(NOT_INSTALLED, guide = guideFor(AdbBackend.SHIZUKU))

        composeTestRule.onNode(hasClickAction() and hasText(installLabel("Shizuku"))).assertIsDisplayed()
    }

    @Test
    fun `the install action goes to the flavor guide`() {
        render(NOT_INSTALLED)

        composeTestRule.onNodeWithText(installLabel("Porter")).performClick()

        actions shouldBe listOf(SetupAction.InstallAdbManager)
    }

    @Test
    fun `a disabled switch offers nothing to open or install`() {
        render(CONNECTED.copy(useShizuku = null))

        assertNoManagerAction()
    }

    @Test
    fun `a client too old for the server offers no button`() {
        render(NOT_CONNECTED.copy(isCompatible = false, clientTooOld = true))

        assertNoManagerAction()
    }

    @Test
    fun `a server too old for Butler opens the manager to update it`() {
        render(NOT_CONNECTED.copy(isCompatible = false, serverTooOld = true))

        composeTestRule.onNode(hasClickAction() and hasText(openLabel("Porter"))).assertIsDisplayed()
    }

    @Test
    fun `a connected card keeps the manager within reach`() {
        render(CONNECTED)

        composeTestRule.onNodeWithText(openLabel("Porter")).performClick()

        actions shouldBe listOf(SetupAction.OpenAdbManager(PORTER))
        composeTestRule.onAllNodes(hasText(installLabel(""), substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a connected server whose manager is gone offers nothing to open`() {
        render(CONNECTED.copy(pkg = null, managerLabel = null))

        composeTestRule
            .onNodeWithText(context.getString(R.string.setup_adb_status_connected_via, AdbBackend.PORTER.label))
            .assertIsDisplayed()
        assertNoManagerAction()
    }

    @Test
    fun `a denied permission opens the manager`() {
        render(
            NOT_CONNECTED.copy(
                permissionState = AdbPermissionState.Denied(permanentlyDenied = false),
                basicService = true,
            )
        )

        composeTestRule.onNode(hasClickAction() and hasText(openLabel("Porter"))).assertIsDisplayed()
    }

    @Test
    fun `a denied permission offers to ask again next to opening the manager`() {
        render(
            NOT_CONNECTED.copy(
                permissionState = AdbPermissionState.Denied(permanentlyDenied = false),
                basicService = true,
                serviceState = ShizukuServiceState.PermissionDenied,
            )
        )

        composeTestRule.onNode(hasClickAction() and hasText(openLabel("Porter"))).assertIsDisplayed()
        composeTestRule.onNode(hasClickAction() and hasText(grantLabel)).assertIsDisplayed()

        composeTestRule.onNodeWithText(grantLabel).performClick()

        actions shouldBe listOf(SetupAction.RequestPermission)
    }

    @Test
    fun `a permanently denied permission opens the manager`() {
        render(
            NOT_CONNECTED.copy(
                permissionState = AdbPermissionState.Denied(permanentlyDenied = true),
                basicService = true,
                serviceState = ShizukuServiceState.PermissionDenied,
            )
        )

        composeTestRule.onNode(hasClickAction() and hasText(openLabel("Porter"))).assertIsDisplayed()
    }

    @Test
    fun `a permanently denied permission offers no grant`() {
        render(
            NOT_CONNECTED.copy(
                permissionState = AdbPermissionState.Denied(permanentlyDenied = true),
                basicService = true,
                serviceState = ShizukuServiceState.PermissionDenied,
            )
        )

        composeTestRule.onAllNodes(hasText(grantLabel)).assertCountEquals(0)
    }

    @Test
    fun `a failed connection opens the manager`() {
        render(NOT_CONNECTED.copy(basicService = true, serviceState = ShizukuServiceState.Failed))

        composeTestRule.onNode(hasClickAction() and hasText(openLabel("Porter"))).assertIsDisplayed()
    }

    @Test
    fun `a pending connection opens the manager`() {
        render(NOT_CONNECTED.copy(basicService = true))

        composeTestRule.onNode(hasClickAction() and hasText(openLabel("Porter"))).assertIsDisplayed()
    }

    @Test
    fun `an unconnected manager can be opened`() {
        render(NOT_CONNECTED)

        composeTestRule.onNode(hasClickAction() and hasText(openLabel("Porter"))).assertIsDisplayed()
    }

    /** A package id is not a name: an unreadable label falls back to the backend's product name. */
    @Test
    fun `an unreadable label falls back to the backend name, never the package id`() {
        render(
            NOT_CONNECTED.copy(
                backend = AdbBackend.SHIZUKU,
                pkg = SHIZUKU_PKG.toPkgId(),
                managerLabel = null,
            )
        )

        composeTestRule.onNode(hasClickAction() and hasText(openLabel(AdbBackend.SHIZUKU.label))).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(SHIZUKU_PKG, substring = true)).assertCountEquals(0)
    }

    companion object {
        private val PORTER = "eu.darken.porter".toPkgId()
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

        private val NOT_INSTALLED = ShizukuSetupModule.Result(useShizuku = true, isInstalled = false)
        private val NOT_CONNECTED = ShizukuSetupModule.Result(
            useShizuku = true,
            backend = AdbBackend.PORTER,
            pkg = PORTER,
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
