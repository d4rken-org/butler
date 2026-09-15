package eu.darken.butler.common.compose

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.math.abs

/**
 * White is the edge of the ramp's input range: the CIELAB round-trip puts pure white marginally
 * above L* 100, so the headroom the generator divides by can go negative. A white suit has no
 * chroma to spread, so every slot it produces has to come back grey.
 */
class MascotRampWhiteSuitTest : BaseTest() {

    private val white = 0xFFFFFF

    private fun rampOf(suit: Int, night: Boolean): List<Int> {
        val outfit = MascotPalette.ramp(suit, night)
        return MascotPalette.DAY.map { outfit.getValue(it) }
    }

    private fun Int.asHex(): String = "#%06x".format(this)

    private fun Int.channels(): Triple<Int, Int, Int> =
        Triple((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)

    /** How far apart the widest pair of channels sits: 0 is a perfect grey. */
    private fun Int.channelSpread(): Int {
        val (r, g, b) = channels()
        return maxOf(abs(r - g), abs(g - b), abs(r - b))
    }

    private fun assertOutfitIsNeutral(night: Boolean) {
        val outfit = rampOf(white, night)
        val tinted = outfit.withIndex().filter { (_, color) -> color.channelSpread() > 1 }
        withClue(
            "night=$night outfit is ${outfit.joinToString { it.asHex() }}, " +
                "tinted slots: " + tinted.joinToString {
                    "${it.index}=${it.value.asHex()} spread ${it.value.channelSpread()}"
                } + " - a white suit has no chroma, so every slot must come back grey"
        ) {
            tinted.isEmpty() shouldBe true
        }
    }

    @Test
    fun `a white suit produces a neutral outfit in the dark theme`() {
        assertOutfitIsNeutral(night = true)
    }

    @Test
    fun `a white suit produces a neutral outfit in the light theme`() {
        assertOutfitIsNeutral(night = false)
    }

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
}
