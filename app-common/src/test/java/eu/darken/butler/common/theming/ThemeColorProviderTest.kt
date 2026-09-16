package eu.darken.butler.common.theming

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.math.pow

class ThemeColorProviderTest : BaseTest() {

    /** [ColorScheme] compares by identity, and its `toString` spells out every role. */
    private fun ColorScheme.roles(): String = toString()

    private fun Color.relativeLuminance(): Double {
        fun channel(value: Float): Double {
            val raw = value.toDouble()
            return if (raw <= 0.03928) raw / 12.92 else ((raw + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    private fun contrastRatio(one: Color, other: Color): Double {
        val first = one.relativeLuminance()
        val second = other.relativeLuminance()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    @Test
    fun `every theme color, style and mode resolves`() {
        ThemeColor.entries.forEach { color ->
            ThemeStyle.entries.forEach { style ->
                listOf(true, false).forEach { dark ->
                    val seed = ThemeState(color = color).themeSeed
                    ThemeColorProvider.getColorScheme(seed, style, dark) shouldNotBe null
                }
            }
        }
    }

    @Test
    fun `high contrast separates primary from surface further than the default does`() {
        ThemeColor.entries.forEach { color ->
            listOf(true, false).forEach { dark ->
                val seed = ThemeState(color = color).themeSeed
                val default = ThemeColorProvider.getColorScheme(seed, ThemeStyle.DEFAULT, dark)
                val high = ThemeColorProvider.getColorScheme(seed, ThemeStyle.HIGH_CONTRAST, dark)

                val defaultRatio = contrastRatio(default.primary, default.surface)
                val highRatio = contrastRatio(high.primary, high.surface)

                withClue("$color dark=$dark: default $defaultRatio vs high $highRatio") {
                    (highRatio > defaultRatio) shouldBe true
                }
            }
        }
    }

    @Test
    fun `the same seed generates the same scheme every time`() {
        val seed = ThemeSeed(Color(0xFF1565C0), ThemePalette.VIBRANT)

        val first = ThemeColorProvider.getColorScheme(seed, ThemeStyle.DEFAULT, dark = false)
        val second = ThemeColorProvider.getColorScheme(seed, ThemeStyle.DEFAULT, dark = false)

        first.roles() shouldBe second.roles()
    }

    @Test
    fun `a stored seed without alpha generates the opaque scheme`() {
        val stored = ThemeState(color = ThemeColor.CUSTOM, customSeed = 0x1565C0)

        stored.themeSeed.seed shouldBe Color(0xFF1565C0)

        val fromStored = ThemeColorProvider.getColorScheme(stored.themeSeed, ThemeStyle.DEFAULT, dark = false)
        val fromOpaque = ThemeColorProvider.getColorScheme(
            ThemeSeed(Color(0xFF1565C0), ThemePalette.TONAL_SPOT),
            ThemeStyle.DEFAULT,
            dark = false,
        )

        fromStored.roles() shouldBe fromOpaque.roles()
    }

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
}
