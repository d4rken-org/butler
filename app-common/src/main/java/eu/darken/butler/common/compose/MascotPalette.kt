package eu.darken.butler.common.compose

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The dark-theme half of the mascot palette, for the Lottie clips.
 *
 * The static drawables get the same treatment from `res/values-night/colors.xml`; a clip is one
 * JSON blob with the colors baked in, so the swap happens on the way to the parser instead. Both
 * halves have to agree, which is what `MascotPaletteTest` checks.
 *
 * Everything that touches the background moves, the hat included. The moustache is drawn wholly on
 * the bright head and carries its own `mascot_ink` value so it stays put.
 */
internal object MascotPalette {

    val NIGHT: Map<Int, Int> = mapOf(
        0x262626 to 0x6e6e6e, // suit
        0x1e1e1e to 0x646464, // trousers
        0x3f3f3f to 0x868686, // lapel shadow
        0x565656 to 0x9a9a9a, // lapel edge, left
        0x666666 to 0xa6a6a6, // lapel edge, right
        0x9b9a9a to 0xcfcfcf, // cuffs
    )

    // A static fill or stroke color: {"a": 0, "k": [r, g, b]}, sometimes with a fourth alpha slot.
    // An animated color has "a": 1 and a keyframe list, which no mascot clip uses.
    private val STATIC_COLOR = Regex("""("c":\s*\{\s*"a":\s*0,\s*"k":\s*\[)([^\]]*)(])""")

    fun forNight(json: String): String = STATIC_COLOR.replace(json) { match ->
        val parts = match.groupValues[2].split(',').map { it.trim() }
        val night = parts.toRgb()?.let { NIGHT[it] }
        when (night) {
            null -> match.value
            else -> {
                val channels = listOf(16, 8, 0).map { shift -> ((night shr shift) and 0xFF).asChannel() }
                match.groupValues[1] + (channels + parts.drop(3)).joinToString(", ") + match.groupValues[3]
            }
        }
    }

    private fun List<String>.toRgb(): Int? {
        if (size < 3) return null
        val channels = take(3).map { it.toFloatOrNull() ?: return null }
        return channels.fold(0) { acc, channel -> (acc shl 8) or (channel * 255).roundToInt() }
    }

    private fun Int.asChannel(): String = String.format(Locale.ROOT, "%.12f", this / 255f)
}
