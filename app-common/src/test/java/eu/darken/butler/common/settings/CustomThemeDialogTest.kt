package eu.darken.butler.common.settings

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemePalette
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.ThemeStyle
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The saturation/brightness square is a bare Canvas with a drag listener, so the seed is driven
 * through the sliders' semantics action rather than by a simulated drag.
 */
@Config(qualifiers = "w400dp-h800dp")
class CustomThemeDialogTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hueLabel = context.getString(R.string.ui_mascot_suit_hue_label)
    private val saturationLabel = context.getString(R.string.ui_mascot_suit_saturation_label)
    private val brightnessLabel = context.getString(R.string.ui_mascot_suit_brightness_label)
    private val defaultAction = context.getString(R.string.ui_theme_custom_default_action)
    private val applyAction = context.getString(R.string.general_apply_action)
    private val monochromeLabel = context.getString(R.string.ui_theme_palette_monochrome_label)

    private var emittedSeed: Int? = null
    private var emittedPalette: ThemePalette? = null
    private var confirmations = 0

    private fun setDialog(
        seed: Int? = null,
        palette: ThemePalette = ThemePalette.TONAL_SPOT,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                CustomThemeDialog(
                    seed = seed,
                    palette = palette,
                    style = ThemeStyle.DEFAULT,
                    onConfirm = { pickedSeed, pickedPalette ->
                        emittedSeed = pickedSeed
                        emittedPalette = pickedPalette
                        confirmations++
                    },
                    onDismiss = {},
                )
            }
        }
    }

    private fun setChannel(label: String, value: Float) = composeTestRule
        .onNodeWithContentDescription(label)
        .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }

    /** Pure green, so no rounding can put the emitted value on a neighboring hex. */
    private fun pickGreen() {
        setChannel(hueLabel, 120f)
        setChannel(saturationLabel, 1f)
        setChannel(brightnessLabel, 1f)
    }

    @Test
    fun `picking a palette style emits it`() {
        setDialog()

        composeTestRule.onNodeWithText(monochromeLabel).performScrollTo().performClick()
        composeTestRule.onNodeWithText(applyAction).performClick()

        confirmations shouldBe 1
        emittedPalette shouldBe ThemePalette.MONOCHROME
    }

    @Test
    fun `an untouched dialog emits what it opened with`() {
        setDialog(seed = 0x00FF00, palette = ThemePalette.VIBRANT)

        composeTestRule.onNodeWithText(applyAction).performClick()

        confirmations shouldBe 1
        emittedSeed shouldBe 0x00FF00
        emittedPalette shouldBe ThemePalette.VIBRANT
    }

    @Test
    fun `the default action clears the seed and the palette`() {
        setDialog(seed = 0x8C1C13, palette = ThemePalette.MONOCHROME)

        composeTestRule.onNodeWithText(defaultAction).performClick()

        confirmations shouldBe 1
        emittedSeed shouldBe null
        emittedPalette shouldBe ThemePalette.TONAL_SPOT
    }

    @Test
    fun `the emitted seed resolves to an opaque color`() {
        setDialog()
        pickGreen()

        composeTestRule.onNodeWithText(applyAction).performClick()

        val state = ThemeState(color = ThemeColor.CUSTOM, customSeed = emittedSeed)
        state.customThemeSeed.seed shouldBe Color(0xFF00FF00)
    }
}
