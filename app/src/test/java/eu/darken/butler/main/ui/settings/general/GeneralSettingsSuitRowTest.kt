package eu.darken.butler.main.ui.settings.general

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.GuidedTourController
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.locale.LocaleManager
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.motd.MotdSettings
import eu.darken.butler.provider.documents.core.DocumentsProviderSettings
import eu.darken.butler.upgrade.UpgradeRepo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.coroutine.TestDispatcherProvider
import eu.darken.butler.common.R as CommonR

/**
 * The suit row is the one appearance row built from `SettingsBaseItem` rather than
 * `SettingsPreferenceItem`, so its upgrade gate is wired at the call site and can drift out of step
 * with the badge it shows.
 */
@Config(qualifiers = "w400dp-h800dp")
class GeneralSettingsSuitRowTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val suitTitle = context.getString(R.string.ui_mascot_suit_setting_label)
    private val modeTitle = context.getString(R.string.ui_theme_mode_setting_label)
    // The dialog's own title repeats the row title verbatim, so its Apply button is what tells the
    // two apart in the semantics tree.
    private val applyAction = context.getString(CommonR.string.general_apply_action)
    private val hueLabel = context.getString(CommonR.string.ui_mascot_suit_hue_label)
    private val saturationLabel = context.getString(CommonR.string.ui_mascot_suit_saturation_label)
    private val brightnessLabel = context.getString(CommonR.string.ui_mascot_suit_brightness_label)

    private var upgrades = 0

    private fun swatchOf(hex: String) = composeTestRule
        .onNodeWithContentDescription(context.getString(CommonR.string.ui_mascot_suit_value_description, hex))

    private fun setScreen(
        isUpgraded: Boolean,
        suitColor: Int? = null,
        onSuitColorSelected: (Int?) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                GeneralSettingsScreen(
                    state = GeneralSettingsViewModel.State(
                        themeState = ThemeState(suitColor = suitColor),
                        isUpgraded = isUpgraded,
                    ),
                    onNavigateUp = {},
                    onLanguageSwitcher = null,
                    onThemeModeSelected = {},
                    onThemeStyleSelected = {},
                    onThemeColorSelected = {},
                    onSuitColorSelected = onSuitColorSelected,
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
    fun `the suit row sits above the theme mode row`() {
        setScreen(isUpgraded = true)

        val suitTop = composeTestRule.onNodeWithText(suitTitle).getBoundsInRoot().top
        val modeTop = composeTestRule.onNodeWithText(modeTitle).getBoundsInRoot().top

        (suitTop < modeTop) shouldBe true
    }

    @Test
    fun `the swatch falls back to the theme default`() {
        setScreen(isUpgraded = true)

        swatchOf("#262626").assertExists()
    }

    @Test
    fun `the swatch shows the picked color`() {
        setScreen(isUpgraded = true, suitColor = 0x8C1C13)

        swatchOf("#8C1C13").assertExists()
    }

    @Test
    fun `an ungated row opens the picker`() {
        setScreen(isUpgraded = true)

        composeTestRule.onNodeWithText(suitTitle).performClick()

        composeTestRule.onNodeWithText(applyAction).assertExists()
        upgrades shouldBe 0
    }

    @Test
    fun `a free user goes to the upgrade instead of the picker`() {
        setScreen(isUpgraded = false)

        composeTestRule.onNodeWithText(suitTitle).performClick()

        upgrades shouldBe 1
        composeTestRule.onNodeWithText(applyAction).assertDoesNotExist()
    }

    @Test
    fun `a confirmed pick reaches the stored preference`() {
        val suitColorValue: DataStoreValue<Int?> = mockk {
            every { flow } returns MutableStateFlow(null)
            coEvery { update(any()) } returns DataStoreValue.Updated(old = null, new = null)
        }
        val vm = createViewModel(suitColorValue)

        setScreen(isUpgraded = true, onSuitColorSelected = { vm.onSuitColorSelected(it) })

        composeTestRule.onNodeWithText(suitTitle).performClick()
        composeTestRule.onNodeWithContentDescription(hueLabel)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        composeTestRule.onNodeWithContentDescription(saturationLabel)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        composeTestRule.onNodeWithContentDescription(brightnessLabel)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        composeTestRule.onNodeWithText(applyAction).performClick()

        val written = slot<(Int?) -> Int?>()
        coVerify { suitColorValue.update(capture(written)) }
        written.captured(null) shouldBe 0x00FF00
    }

    private fun createViewModel(suitColorValue: DataStoreValue<Int?>): GeneralSettingsViewModel {
        val info: UpgradeRepo.Info = mockk { every { isPro } returns true }
        val generalSettings: GeneralSettings = mockk {
            every { themeMode } returns mockk { every { flow } returns MutableStateFlow(ThemeMode.SYSTEM) }
            every { themeStyle } returns mockk { every { flow } returns MutableStateFlow(ThemeStyle.DEFAULT) }
            every { themeColor } returns mockk { every { flow } returns MutableStateFlow(ThemeColor.GREEN) }
            every { mascotSuitColor } returns suitColorValue
            every { isUpdateCheckEnabled } returns mockk { every { flow } returns MutableStateFlow(true) }
            every { isConfirmExitEnabled } returns mockk { every { flow } returns MutableStateFlow(true) }
            every { isDisplayCutoutAvoided } returns mockk { every { flow } returns MutableStateFlow(true) }
        }
        return GeneralSettingsViewModel(
            dispatcherProvider = TestDispatcherProvider(),
            generalSettings = generalSettings,
            guidedTourController = mockk<GuidedTourController>(relaxed = true),
            localeManager = mockk<LocaleManager>(relaxed = true),
            motdSettings = mockk<MotdSettings> {
                every { isMotdEnabled } returns mockk { every { flow } returns MutableStateFlow(true) }
            },
            documentsProviderSettings = mockk<DocumentsProviderSettings> {
                every { isEnabled } returns mockk { every { flow } returns MutableStateFlow(true) }
            },
            upgradeRepo = mockk { every { upgradeInfo } returns flowOf(info) },
        )
    }
}
