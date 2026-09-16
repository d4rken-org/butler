package eu.darken.butler.common.settings

import androidx.compose.ui.graphics.Color
import eu.darken.butler.common.theming.ThemePalette
import eu.darken.butler.common.theming.ThemeStyle
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class CustomThemePreviewSwatchesTest : BaseTest() {

    private val seed = Color(0xFF1565C0)
    private val palette = ThemePalette.TONAL_SPOT

    @Test
    fun `the contrast level changes the preview swatches`() {
        listOf(true, false).forEach { dark ->
            val highContrast = customThemePreviewSwatches(
                seed = seed,
                palette = palette,
                style = ThemeStyle.HIGH_CONTRAST,
                dark = dark,
            )
            val default = customThemePreviewSwatches(
                seed = seed,
                palette = palette,
                style = ThemeStyle.DEFAULT,
                dark = dark,
            )
            highContrast shouldNotBe default
        }
    }
}
