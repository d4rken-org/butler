package eu.darken.butler.common.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The suit swap has to survive the artwork arriving in either theme's colors. The fills resolve
 * against the theme handed to the vector parser, which follows the system night configuration
 * rather than the resources the XML is read from, so a night themed mascot can reach [repainted]
 * carrying the night fills rather than the day ones the outfit map is keyed on.
 */
class MascotNightVectorRepaintTest : BaseTest() {

    /** A suit nowhere near either shipped outfit, so a missed swap cannot pass by coincidence. */
    private val crimson = 0xc21807

    private val crimsonOutfit = MascotPalette.ramp(crimson, night = true)

    /** Shirt white: no outfit map has a key for it, so it has to come through untouched. */
    private val shirt = 0xf2f2f2

    private val dayFills = MascotPalette.DAY

    private val nightFills = MascotPalette.DAY.map { MascotPalette.NIGHT.getValue(it) }

    private val crimsonFills = MascotPalette.DAY.map { crimsonOutfit.getValue(it) }

    private fun vectorOf(fills: List<Int>): ImageVector {
        val builder = ImageVector.Builder(
            name = "mascot_test",
            defaultWidth = 512.dp,
            defaultHeight = 512.dp,
            viewportWidth = 512f,
            viewportHeight = 512f,
        )
        fills.forEach { fill ->
            builder.addPath(
                pathData = PathData {
                    moveTo(0f, 0f)
                    lineTo(1f, 0f)
                    lineTo(1f, 1f)
                    close()
                },
                fill = SolidColor(Color(fill or OPAQUE)),
            )
        }
        return builder.build()
    }

    private fun fillsOf(vector: ImageVector): List<Int> = vector.root
        .map { it as VectorPath }
        .map { (it.fill as SolidColor).value.toArgb() and 0xFFFFFF }

    private fun assertRepaints(baseline: String, source: List<Int>) {
        val after = fillsOf(vectorOf(source + shirt).repainted(crimsonOutfit))
        withClue(
            "a $baseline mascot repainted onto ${crimson.asHex()} came back as " +
                after.joinToString { it.asHex() } +
                ", expected " + (crimsonFills + shirt).joinToString { it.asHex() },
        ) {
            after shouldBe crimsonFills + shirt
        }
    }

    @Test
    fun `a day themed vector is repainted onto the picked suit`() {
        assertRepaints("day themed", dayFills)
    }

    @Test
    fun `a night themed vector is repainted onto the picked suit`() {
        assertRepaints("night themed", nightFills)
    }

    private fun Int.asHex(): String = "#%06x".format(this)

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
    }
}
