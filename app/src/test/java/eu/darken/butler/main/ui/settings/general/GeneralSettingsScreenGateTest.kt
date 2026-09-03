package eu.darken.butler.main.ui.settings.general

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.ThemeStyle
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import eu.darken.butler.common.R as CommonR

@Config(qualifiers = "w400dp-h800dp")
class GeneralSettingsScreenGateTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val badgeLabel = context.getString(CommonR.string.app_name_upgrade_postfix)
    private val modeTitle = context.getString(R.string.ui_theme_mode_setting_label)
    private val styleTitle = context.getString(R.string.ui_theme_style_setting_label)
    private val colorTitle = context.getString(R.string.ui_theme_color_setting_label)

    /** Options that only ever appear inside the respective dialog, never as a row value here. */
    private val modeDialogOption = context.getString(CommonR.string.ui_theme_mode_dark_label)
    private val styleDialogOption = context.getString(CommonR.string.ui_theme_style_high_contrast_label)

    private var upgrades = 0

    private fun setScreen(
        isUpgraded: Boolean,
        style: ThemeStyle = ThemeStyle.DEFAULT,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                GeneralSettingsScreen(
                    state = GeneralSettingsViewModel.State(
                        themeState = ThemeState(style = style),
                        isUpgraded = isUpgraded,
                    ),
                    onNavigateUp = {},
                    onLanguageSwitcher = null,
                    onThemeModeSelected = {},
                    onThemeStyleSelected = {},
                    onThemeColorSelected = {},
                    onUpgradeButler = { upgrades++ },
                    onUpdateCheckEnabledChange = {},
                    onMotdEnabledChange = {},
                    onConfirmExitEnabledChange = {},
                    onAvoidDisplayCutoutChange = {},
                    onDocumentsProviderEnabledChange = {},
                    onNavigateToPreviews = {},
                    onNavigateToShortcuts = {},
                    onResetGuidedTours = {},
                )
            }
        }
    }

    @Test
    fun `the gated theme mode row goes to the upgrade instead of the dialog`() {
        setScreen(isUpgraded = false)

        composeTestRule.onNodeWithText(modeTitle).performClick()
        upgrades shouldBe 1
        composeTestRule.onNodeWithText(modeDialogOption).assertDoesNotExist()
    }

    @Test
    fun `the gated theme style row goes to the upgrade instead of the dialog`() {
        setScreen(isUpgraded = false)

        composeTestRule.onNodeWithText(styleTitle).performClick()
        upgrades shouldBe 1
        composeTestRule.onNodeWithText(styleDialogOption).assertDoesNotExist()
    }

    @Test
    fun `pro users open the theme mode dialog`() {
        setScreen(isUpgraded = true)

        composeTestRule.onNodeWithText(modeTitle).performClick()
        composeTestRule.onNodeWithText(modeDialogOption).assertIsDisplayed()
        upgrades shouldBe 0
    }

    @Test
    fun `pro users open the theme style dialog`() {
        setScreen(isUpgraded = true)

        composeTestRule.onNodeWithText(styleTitle).performClick()
        composeTestRule.onNodeWithText(styleDialogOption).assertIsDisplayed()
        upgrades shouldBe 0
    }

    @Test
    fun `free users get a badge on all three theme rows`() {
        setScreen(isUpgraded = false)

        composeTestRule.onAllNodesWithText(badgeLabel).assertCountEquals(3)
        composeTestRule.onNodeWithText(colorTitle).assertIsEnabled()
    }

    @Test
    fun `material you takes the color row out of the gate`() {
        setScreen(isUpgraded = false, style = ThemeStyle.MATERIAL_YOU)

        // Mode and style only - upgrading would not unlock a row the theme style owns.
        composeTestRule.onAllNodesWithText(badgeLabel).assertCountEquals(2)
        composeTestRule.onNodeWithText(colorTitle).assertIsNotEnabled()
    }

    @Test
    fun `pro users get no badge at all`() {
        setScreen(isUpgraded = true)

        composeTestRule.onAllNodesWithText(badgeLabel).assertCountEquals(0)
        composeTestRule.onNodeWithText(colorTitle).assertIsEnabled()
    }

    @Test
    fun `material you disables the color row for pro users too`() {
        setScreen(isUpgraded = true, style = ThemeStyle.MATERIAL_YOU)

        composeTestRule.onAllNodesWithText(badgeLabel).assertCountEquals(0)
        composeTestRule.onNodeWithText(colorTitle).assertIsNotEnabled()
    }
}
