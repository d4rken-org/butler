package eu.darken.butler.common.compose

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The butler's outfit, and how to repaint it onto another suit color.
 *
 * The static drawables get their values from `res/values/colors.xml` and `res/values-night`; a clip
 * is one JSON blob with the colors baked in, so the swap happens on the way to the parser instead.
 * Both halves have to agree, which is what `MascotPaletteTest` checks.
 *
 * Everything that touches the background moves, the hat included. The moustache and tie carry
 * their own `mascot_ink` value so they stay dark against the head and white shirt.
 */
internal object MascotPalette {

    private const val SUIT = 0

    /** Slot order: suit, trousers, lapel shadow, lapel edge left, lapel edge right, cuffs. */
    val DAY: List<Int> = listOf(0x262626, 0x1e1e1e, 0x3f3f3f, 0x565656, 0x666666, 0x9b9a9a)

    /**
     * The silver outfit the dark theme used to wear, kept as the L* shape for a light suit: a
     * jacket with room to lighten puts its lapel shadow below the suit, which [DAY] cannot say.
     */
    private val SILVER_SHAPE: List<Int> = listOf(0xaeb8b2, 0x929e97, 0x808c85, 0xc5cec8, 0xdce3de, 0xedf1ee)

    /** What `res/values-night/colors.xml` holds, and what the clips are recolored to. */
    val NIGHT: Map<Int, Int> = ramp(0x3d4a63, night = true)

    /** The suit the butler wears when nobody picked one. */
    fun suitColor(night: Boolean): Int = if (night) NIGHT.getValue(DAY[SUIT]) else DAY[SUIT]

    /**
     * A whole outfit built from one suit color, keyed on [DAY] so it can be applied to either
     * format. Every other slot keeps its L* distance to the suit and takes the suit's own chroma,
     * faded towards grey as the slot lightens so the cuffs read as a tint rather than a block.
     *
     * A suit below L* 50 takes [DAY]'s offsets, a lighter one [SILVER_SHAPE]'s. [night] picks
     * nothing: the shape follows the suit's own lightness, which is the property that decides it.
     * It stays in the signature because both call sites have to name the mode they are asking for.
     */
    fun ramp(suit: Int, night: Boolean): Map<Int, Int> {
        val (suitL, suitA, suitB) = suit.toLab()
        val shape = if (suitL < 50.0) DAY else SILVER_SHAPE
        val shapeSuitL = shape[SUIT].toLab().l

        val outfit = DAY.indices.associate { slot ->
            val lightness = (suitL + shape[slot].toLab().l - shapeSuitL).coerceIn(2.0, 98.0)
            val headroom = if (lightness <= suitL) {
                0.0
            } else {
                ((lightness - suitL) / (100.0 - suitL)).coerceIn(0.0, 1.0)
            }
            val chroma = 1.0 - 0.45 * headroom
            DAY[slot] to labToRgb(lightness, suitA * chroma, suitB * chroma)
        }
        // The picked color itself, never round-tripped through Lab
        return outfit + (DAY[SUIT] to suit)
    }

    /**
     * [outfit] plus a night-keyed alias of every slot. The artwork's fills resolve against the
     * theme handed to the vector parser, and that theme follows the system night configuration
     * rather than the resources the XML is read from, so the repaint can be handed either
     * baseline's fills. [DAY] and [NIGHT]'s values are disjoint, so one map can answer for both.
     */
    fun rampForEitherBaseline(outfit: Map<Int, Int>): Map<Int, Int> =
        outfit + NIGHT.entries.associate { (dayColor, nightColor) -> nightColor to outfit.getValue(dayColor) }

    // A static fill or stroke color: {"a": 0, "k": [r, g, b]}, sometimes with a fourth alpha slot.
    // An animated color has "a": 1 and a keyframe list, which no mascot clip uses.
    private val STATIC_COLOR = Regex("""("c":\s*\{\s*"a":\s*0,\s*"k":\s*\[)([^\]]*)(])""")

    fun recolor(json: String, map: Map<Int, Int>): String = STATIC_COLOR.replace(json) { match ->
        val parts = match.groupValues[2].split(',').map { it.trim() }
        val repainted = parts.toRgb()?.let { map[it] }
        when (repainted) {
            null -> match.value
            else -> {
                val channels = listOf(16, 8, 0).map { shift -> ((repainted shr shift) and 0xFF).asChannel() }
                match.groupValues[1] + (channels + parts.drop(3)).joinToString(", ") + match.groupValues[3]
            }
        }
    }

    fun forNight(json: String): String = recolor(json, NIGHT)

    private fun List<String>.toRgb(): Int? {
        if (size < 3) return null
        val channels = take(3).map { it.toFloatOrNull() ?: return null }
        return channels.fold(0) { acc, channel -> (acc shl 8) or (channel * 255).roundToInt() }
    }

    private fun Int.asChannel(): String = String.format(Locale.ROOT, "%.12f", this / 255f)

    private data class Lab(val l: Double, val a: Double, val b: Double)

    // CIELAB against the D65 white point, the one sRGB is defined for.
    private const val WHITE_X = 0.95047
    private const val WHITE_Z = 1.08883
    private const val LINEAR_CUTOFF = 216.0 / 24389.0

    private fun Int.toLab(): Lab {
        val r = ((this shr 16) and 0xFF).toLinear()
        val g = ((this shr 8) and 0xFF).toLinear()
        val b = (this and 0xFF).toLinear()
        val fx = ((0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / WHITE_X).pivot()
        val fy = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b).pivot()
        val fz = ((0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / WHITE_Z).pivot()
        return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun labToRgb(l: Double, a: Double, b: Double): Int {
        val fy = (l + 16.0) / 116.0
        val x = (fy + a / 500.0).unpivot() * WHITE_X
        val y = fy.unpivot()
        val z = (fy - b / 200.0).unpivot() * WHITE_Z
        val r = (3.2404542 * x - 1.5371385 * y - 0.4985314 * z).toChannel()
        val g = (-0.9692660 * x + 1.8760108 * y + 0.0415560 * z).toChannel()
        val blue = (0.0556434 * x - 0.2040259 * y + 1.0572252 * z).toChannel()
        return (r shl 16) or (g shl 8) or blue
    }

    private fun Int.toLinear(): Double {
        val c = this / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun Double.toChannel(): Int {
        val c = if (this <= 0.0031308) this * 12.92 else 1.055 * pow(1.0 / 2.4) - 0.055
        return (c.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    }

    private fun Double.pivot(): Double =
        if (this > LINEAR_CUTOFF) pow(1.0 / 3.0) else (841.0 / 108.0) * this + 4.0 / 29.0

    private fun Double.unpivot(): Double {
        val cubed = this * this * this
        return if (cubed > LINEAR_CUTOFF) cubed else (this - 4.0 / 29.0) * 108.0 / 841.0
    }
}
