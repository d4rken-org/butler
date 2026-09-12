package eu.darken.butler.setup.ui.items

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The restart hint has to survive every value of the use-ADB switch: it sits ahead of that guard
 * because the setting still being unset is the most likely way to reach this state.
 */
class ShizukuSetupCardStatusTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun renderOtherManagerInstalled(useShizuku: Boolean?) {
        composeTestRule.setContent {
            PreviewWrapper {
                RootShizukuActions(
                    item = SetupItem(
                        type = SetupModule.Type.SHIZUKU,
                        state = ShizukuSetupModule.Result(
                            pkg = "eu.darken.porter".toPkgId(),
                            useShizuku = useShizuku,
                            isCompatible = true,
                            isInstalled = false,
                            restartRequiredFor = OTHER_MANAGER.toPkgId(),
                            restartRequiredLabel = OTHER_LABEL,
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

    private fun assertRestartHintShown() {
        composeTestRule
            .onNodeWithText(context.getString(R.string.setup_adb_restart_required, OTHER_LABEL))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(context.getString(R.string.setup_status_not_installed)).assertCountEquals(0)
    }

    /** Falls back to the other backend's product name, so the hint never renders a bare placeholder. */
    @Test
    fun `the hint names the backend when the label cannot be read`() {
        composeTestRule.setContent {
            PreviewWrapper {
                RootShizukuActions(
                    item = SetupItem(
                        type = SetupModule.Type.SHIZUKU,
                        state = ShizukuSetupModule.Result(
                            pkg = "eu.darken.porter".toPkgId(),
                            useShizuku = true,
                            isCompatible = true,
                            isInstalled = false,
                            backend = AdbBackend.PORTER,
                            restartRequiredFor = OTHER_MANAGER.toPkgId(),
                            restartRequiredLabel = null,
                        ),
                        isRequired = false,
                        priority = 6,
                    ),
                    onExecuteAction = {},
                    switchLabel = context.getString(R.string.setup_use_shizuku_label),
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.setup_adb_restart_required, AdbBackend.SHIZUKU.label))
            .assertIsDisplayed()
    }

    @Test
    fun `the hint shows while the switch was never touched`() {
        renderOtherManagerInstalled(null)

        assertRestartHintShown()
    }

    @Test
    fun `the hint shows while the switch is off`() {
        renderOtherManagerInstalled(false)

        assertRestartHintShown()
    }

    @Test
    fun `the hint shows while the switch is on`() {
        renderOtherManagerInstalled(true)

        assertRestartHintShown()
    }

    companion object {
        private const val OTHER_MANAGER = "moe.shizuku.privileged.api"
        private const val OTHER_LABEL = "Shizuku"
    }
}
