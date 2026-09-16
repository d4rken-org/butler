package eu.darken.butler.common.theming

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Properties every generated scheme has to hold, whatever the generator does internally: surfaces
 * stay off Material's purple baseline, AMOLED stays pure black, and a preset keeps the hue of the
 * seed it is wired to.
 */
class ButlerSchemeSurfaceHueTest : BaseTest() {

    private companion object {
        /** Degrees on the HSV wheel. */
        const val HUE_TOLERANCE = 20.0

        /** Below this HSV saturation a colour carries no hue worth comparing. */
        const val ACHROMATIC_SATURATION = 0.01

        val STYLES = listOf(ThemeStyle.DEFAULT, ThemeStyle.MEDIUM_CONTRAST, ThemeStyle.HIGH_CONTRAST)
    }

    private fun schemes(dark: Boolean): List<Triple<ThemeColor, ThemeStyle, ColorScheme>> =
        ThemeColor.entries.flatMap { color ->
            STYLES.map { style ->
                Triple(
                    color,
                    style,
                    ThemeColorProvider.getColorScheme(ThemeState(color = color).themeSeed, style, dark),
                )
            }
        }

    private fun Color.asHex(): String = "#%02x%02x%02x".format(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )

    /** HSV saturation, 0 for a perfect grey. */
    private fun Color.saturation(): Double {
        val max = maxOf(red, green, blue).toDouble()
        val min = minOf(red, green, blue).toDouble()
        return if (max <= 0.0) 0.0 else (max - min) / max
    }

    /** HSV hue in degrees, or null when the colour is too close to grey to have one. */
    private fun Color.hue(): Double? {
        if (saturation() < ACHROMATIC_SATURATION) return null
        val r = red.toDouble()
        val g = green.toDouble()
        val b = blue.toDouble()
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        val hue = when (max) {
            r -> 60.0 * (((g - b) / delta) % 6.0)
            g -> 60.0 * (((b - r) / delta) + 2.0)
            else -> 60.0 * (((r - g) / delta) + 4.0)
        }
        return (hue + 360.0) % 360.0
    }

    /** Shortest distance between two hues on the wheel, 0-180 degrees. */
    private fun hueDistance(one: Double, other: Double): Double {
        val raw = abs(one - other) % 360.0
        return if (raw > 180.0) 360.0 - raw else raw
    }

    @Test
    fun `no generated surface lands on the Material baseline`() {
        val baseline = lightColorScheme().surfaceContainerHigh

        val offenders = schemes(dark = false).mapNotNull { (color, style, scheme) ->
            if (scheme.surfaceContainerHigh != baseline) return@mapNotNull null
            "$color.$style surfaceContainerHigh is the baseline ${baseline.asHex()}"
        }

        withClue("schemes whose surfaceContainerHigh is Material's own:\n" + offenders.joinToString("\n")) {
            offenders.isEmpty() shouldBe true
        }
    }

    @Test
    fun `the AMOLED preset is pure black in the dark`() {
        STYLES.forEach { style ->
            val scheme = ThemeColorProvider.getColorScheme(
                ThemeColor.AMOLED.preset!!,
                style,
                dark = true,
            )
            withClue("$style surface is ${scheme.surface.asHex()}") {
                scheme.surface shouldBe Color.Black
            }
            withClue("$style background is ${scheme.background.asHex()}") {
                scheme.background shouldBe Color.Black
            }
        }
    }

    @Test
    fun `each preset keeps the hue of its seed`() {
        val offenders = ThemeColor.entries.mapNotNull { color ->
            val seed = color.preset ?: return@mapNotNull null
            val seedHue = seed.seed.hue() ?: return@mapNotNull null
            val primary = ThemeColorProvider.getColorScheme(seed, ThemeStyle.DEFAULT, dark = false).primary
            val primaryHue = primary.hue() ?: return@mapNotNull "$color primary ${primary.asHex()} has no hue"
            val distance = hueDistance(primaryHue, seedHue)
            if (distance <= HUE_TOLERANCE) return@mapNotNull null
            "$color primary is ${primary.asHex()} at hue ${"%.0f".format(primaryHue)}deg while its " +
                "seed ${seed.seed.asHex()} is at ${"%.0f".format(seedHue)}deg, " +
                "${"%.0f".format(distance)}deg apart"
        }

        withClue("presets whose primary drifted off the seed:\n" + offenders.joinToString("\n")) {
            offenders.isEmpty() shouldBe true
        }
    }

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
}
