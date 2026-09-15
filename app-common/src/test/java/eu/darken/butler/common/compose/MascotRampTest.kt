package eu.darken.butler.common.compose

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.math.abs

/**
 * The generator behind both shipped outfits. It runs on whatever hex a user picks, so a regression
 * here reaches the drawables and the clips at once, and the two shipped ramps are the only fixed
 * points it can be measured against.
 */
class MascotRampTest : BaseTest() {

    private val dayRamp = listOf(0x262626, 0x1e1e1e, 0x3f3f3f, 0x565656, 0x666666, 0x9b9a9a)
    private val silverRamp = listOf(0xaeb8b2, 0x929e97, 0x808c85, 0xc5cec8, 0xdce3de, 0xedf1ee)

    /** The navy the user compared and picked, which `res/values-night/colors.xml` has to match. */
    private val navyRamp = listOf(0x3d4a63, 0x34415a, 0x5a657e, 0x747e96, 0x858fa6, 0xbec6da)

    private fun rampOf(suit: Int, night: Boolean): List<Int> {
        val outfit = MascotPalette.ramp(suit, night)
        return MascotPalette.DAY.map { outfit.getValue(it) }
    }

    private fun Int.asHex(): String = "#%06x".format(this)

    private fun assertClose(actual: List<Int>, expected: List<Int>, tolerance: Int) {
        actual.zip(expected).forEachIndexed { slot, (got, want) ->
            listOf(16, 8, 0).forEach { shift ->
                val delta = abs(((got shr shift) and 0xFF) - ((want shr shift) and 0xFF))
                withClue("slot $slot is ${got.asHex()}, expected ${want.asHex()}") {
                    (delta <= tolerance) shouldBe true
                }
            }
        }
    }

    @Test
    fun `the navy ramp is exactly what the dark theme ships`() {
        rampOf(0x3d4a63, night = true) shouldBe navyRamp
        MascotPalette.DAY.map { MascotPalette.NIGHT.getValue(it) } shouldBe navyRamp
    }

    @Test
    fun `the day suit reproduces the day ramp`() {
        assertClose(rampOf(0x262626, night = false), dayRamp, tolerance = 2)
    }

    @Test
    fun `a light suit reproduces the silver shape it is built from`() {
        assertClose(rampOf(0xaeb8b2, night = true), silverRamp, tolerance = 2)
    }

    @Test
    fun `the suit slot is the picked color itself`() {
        listOf(0x000000, 0xffffff, 0x3d4a63, 0xc21807, 0x48ff80).forEach { suit ->
            withClue(suit.asHex()) { rampOf(suit, night = true).first() shouldBe suit }
        }
    }

    @Test
    fun `every generated value is a legal sRGB hex`() {
        (0..0xFFFFFF step 0x4321).forEach { suit ->
            rampOf(suit, night = false).forEach { generated ->
                withClue("${suit.asHex()} produced $generated") {
                    (generated in 0..0xFFFFFF) shouldBe true
                }
            }
        }
    }

    @Test
    fun `the generator is deterministic`() {
        listOf(0x3d4a63, 0x262626, 0xaeb8b2).forEach { suit ->
            MascotPalette.ramp(suit, night = true) shouldBe MascotPalette.ramp(suit, night = true)
        }
    }

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
}
