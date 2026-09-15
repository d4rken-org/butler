package eu.darken.butler.common.compose

import android.content.Context
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res.vectorResource
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

/**
 * Repainting a drawable means taking it apart and putting it back together. Every path property
 * that is not copied across silently reverts to a Compose default, and `fillType="evenOdd"` is the
 * one that shows: without it the eye and mouth cut-outs fill in solid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class MascotRecolorTest : BaseTest() {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val drawables = mapOf(
        "mascot_normal" to R.drawable.mascot_normal,
        "mascot_happy" to R.drawable.mascot_happy,
        "mascot_sad" to R.drawable.mascot_sad,
        "mascot_ko" to R.drawable.mascot_ko,
    )

    /** Ink, collar, shirt and head: everything the outfit map has no key for. */
    private val offOutfit = setOf(0x212121, 0xf2f2f2, 0xffffff, 0x48ff80)

    private val navy = MascotPalette.ramp(0x3d4a63, night = true)

    private fun load(resId: Int): ImageVector =
        ImageVector.vectorResource(context.theme, context.resources, resId)

    private fun paths(vector: ImageVector): List<VectorPath> = vector.root.map { it as VectorPath }

    private fun fillCounts(vector: ImageVector): Map<Int, Int> = paths(vector)
        .mapNotNull { (it.fill as? SolidColor)?.value?.toArgb()?.and(0xFFFFFF) }
        .groupingBy { it }
        .eachCount()

    @Test
    fun `the outfit fills move to the new ramp and nothing else does`() {
        drawables.forEach { (name, resId) ->
            val before = fillCounts(load(resId))
            val after = fillCounts(load(resId).repainted(navy))

            navy.forEach { (day, night) ->
                withClue("$name slot ${day.asHex()}") {
                    after[night] shouldBe before[day]
                    after[day] shouldBe null
                }
            }
            offOutfit.forEach { color ->
                withClue("$name kept ${color.asHex()}") { after[color] shouldBe before[color] }
            }
        }
    }

    @Test
    fun `every path keeps its fill type`() {
        drawables.forEach { (name, resId) ->
            val before = paths(load(resId)).map { it.pathFillType }
            val after = paths(load(resId).repainted(navy)).map { it.pathFillType }

            withClue("$name has no even-odd path left to protect") {
                before.contains(PathFillType.EvenOdd) shouldBe true
            }
            withClue(name) { after shouldBe before }
        }
    }

    private fun Int.asHex(): String = "#%06x".format(this)

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
}
