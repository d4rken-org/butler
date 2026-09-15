package eu.darken.butler.common.theming

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Butler's palettes only spell out a handful of surface roles; `lightColorScheme`/`darkColorScheme`
 * fill the rest in from Material's own baseline, which is tinted towards purple. A role left to the
 * baseline therefore lands at the baseline's hue instead of the palette's, and any component that
 * uses it sits next to Butler surfaces in a foreign cast.
 *
 * The check is hue only: tone is what separates the container roles from each other, so it must be
 * free to move, while the hue is what the palette owns. [HUE_TOLERANCE] is wide enough for the hue
 * wobble a near-neutral tint picks up from 8-bit quantization and far narrower than the 26-171
 * degrees the Material baseline sits away from Butler's surfaces.
 */
class ButlerSchemeSurfaceHueTest : BaseTest() {

    private companion object {
        /** Degrees on the HSV wheel. */
        const val HUE_TOLERANCE = 20.0

        /** Below this HSV saturation a colour carries no hue worth comparing. */
        const val ACHROMATIC_SATURATION = 0.01
    }

    private fun variants(
        theme: String,
        lightDefault: ColorScheme,
        darkDefault: ColorScheme,
        lightMedium: ColorScheme,
        darkMedium: ColorScheme,
        lightHigh: ColorScheme,
        darkHigh: ColorScheme,
    ): List<Triple<String, String, ColorScheme>> = listOf(
        "LightDefault" to lightDefault,
        "DarkDefault" to darkDefault,
        "LightMediumContrast" to lightMedium,
        "DarkMediumContrast" to darkMedium,
        "LightHighContrast" to lightHigh,
        "DarkHighContrast" to darkHigh,
    ).map { (variant, scheme) -> Triple(theme, variant, scheme) }

    private val schemes: List<Triple<String, String, ColorScheme>> =
        variants(
            "Green",
            ButlerColorsGreen.LightDefault,
            ButlerColorsGreen.DarkDefault,
            ButlerColorsGreen.LightMediumContrast,
            ButlerColorsGreen.DarkMediumContrast,
            ButlerColorsGreen.LightHighContrast,
            ButlerColorsGreen.DarkHighContrast,
        ) + variants(
            "Blue",
            ButlerColorsBlue.LightDefault,
            ButlerColorsBlue.DarkDefault,
            ButlerColorsBlue.LightMediumContrast,
            ButlerColorsBlue.DarkMediumContrast,
            ButlerColorsBlue.LightHighContrast,
            ButlerColorsBlue.DarkHighContrast,
        ) + variants(
            "Amoled",
            ButlerColorsAmoled.LightDefault,
            ButlerColorsAmoled.DarkDefault,
            ButlerColorsAmoled.LightMediumContrast,
            ButlerColorsAmoled.DarkMediumContrast,
            ButlerColorsAmoled.LightHighContrast,
            ButlerColorsAmoled.DarkHighContrast,
        )

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

    private fun report(label: String, role: Color, surface: Color): String? {
        val surfaceHue = surface.hue() ?: return null
        val roleHue = role.hue() ?: return null
        val distance = hueDistance(roleHue, surfaceHue)
        if (distance <= HUE_TOLERANCE) return null
        return "$label is ${role.asHex()} at hue ${"%.0f".format(roleHue)}deg while surface is " +
            "${surface.asHex()} at hue ${"%.0f".format(surfaceHue)}deg, " +
            "${"%.0f".format(distance)}deg apart"
    }

    @Test
    fun `surfaceContainerHigh carries the palette's own hue`() {
        val offenders = schemes.mapNotNull { (theme, variant, scheme) ->
            report("$theme.$variant surfaceContainerHigh", scheme.surfaceContainerHigh, scheme.surface)
        }
        withClue(
            "schemes whose surfaceContainerHigh is off the palette's hue:\n" +
                offenders.joinToString("\n")
        ) {
            offenders.isEmpty() shouldBe true
        }
    }

    /**
     * The control for the criterion above: `surfaceVariant` is spelled out in every palette, so it
     * has to clear the same bar. If this one ever goes red the tolerance is wrong, not the palette.
     */
    @Test
    fun `the hand-written surfaceVariant already clears the same bar`() {
        val offenders = schemes.mapNotNull { (theme, variant, scheme) ->
            report("$theme.$variant surfaceVariant", scheme.surfaceVariant, scheme.surface)
        }
        withClue(
            "hand-written surface roles that fail the criterion:\n" + offenders.joinToString("\n")
        ) {
            offenders.isEmpty() shouldBe true
        }
    }

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
}
