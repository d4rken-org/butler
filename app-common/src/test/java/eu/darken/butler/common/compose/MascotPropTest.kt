package eu.darken.butler.common.compose

import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.Color
import com.airbnb.lottie.LottieCompositionFactory
import eu.darken.butler.common.Occasions
import eu.darken.butler.common.R
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

/**
 * The swap finds the cup and the steam by layer name, which nothing in the clips promises. A
 * re-export that renames them would leave the coffee cup in place; these tests make that loud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class MascotPropTest : BaseTest() {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun rawJson(resId: Int): String =
        context.resources.openRawResource(resId).bufferedReader().use { it.readText() }

    private fun layers(json: String): List<JsonObject> =
        Json.parseToJsonElement(json).jsonObject.getValue("layers").jsonArray.map { it.jsonObject }

    private val JsonObject.name: String? get() = this["nm"]?.jsonPrimitive?.content

    @Test
    fun `drink clips carry one cup and the steam`() {
        MascotProp.DRINK_CLIPS.forEach { clip ->
            val names = layers(rawJson(clip)).map { it.name }
            names.count { it == MascotProp.CUP_LAYER } shouldBe 1
            MascotProp.STEAM_LAYERS.forEach { steam -> names.count { it == steam } shouldBe 1 }
        }
    }

    @Test
    fun `swap replaces only the cup's shapes`() {
        MascotProp.DRINK_CLIPS.forEach { clip ->
            MascotProp.entries.forEach { prop ->
                val original = layers(rawJson(clip))
                val shapes = Json.parseToJsonElement(rawJson(prop.shapes)).jsonArray
                val swapped = layers(MascotProp.swap(rawJson(clip), rawJson(prop.shapes), steaming = true))

                swapped.size shouldBe original.size
                original.zip(swapped).forEach { (before, after) ->
                    if (before.name == MascotProp.CUP_LAYER) {
                        after.getValue("shapes") shouldBe shapes
                        (after - "shapes") shouldBe (before - "shapes")
                    } else {
                        after shouldBe before
                    }
                }
            }
        }
    }

    @Test
    fun `cold props drop the steam`() {
        MascotProp.DRINK_CLIPS.forEach { clip ->
            val original = layers(rawJson(clip))
            val swapped = layers(MascotProp.swap(rawJson(clip), rawJson(MascotProp.STEIN.shapes), steaming = false))

            swapped.map { it.name } shouldBe original.map { it.name }.filterNot { it in MascotProp.STEAM_LAYERS }
        }
    }

    @Test
    fun `every prop parses into both clips`() {
        MascotProp.DRINK_CLIPS.forEach { clip ->
            MascotProp.entries.forEach { prop ->
                val json = MascotProp.swap(rawJson(clip), rawJson(prop.shapes), prop.steaming)
                val result = LottieCompositionFactory.fromJsonStringSync(json, null)
                result.exception shouldBe null
                result.value!!.layers.count { it.name == MascotProp.CUP_LAYER } shouldBe 1
            }
        }
    }

    // The "c" key is what makes it a fill or stroke color rather than three numbers
    private fun colorsIn(json: String): Set<Int> =
        Regex(""""c":\s*\{\s*"a":\s*0,\s*"k":\s*\[([0-9.,\s]+)]""")
            .findAll(json)
            .mapNotNull { match ->
                val channels = match.groupValues[1].split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (channels.size < 3) return@mapNotNull null
                channels.take(3).fold(0) { acc, channel -> (acc shl 8) or Math.round(channel * 255) }
            }
            .toSet()

    private fun dressedDrink(night: Boolean, suit: Color?, prop: MascotProp?): Set<Int> = colorsIn(
        dressClip(rawJson(R.raw.mascot_lottie_drink_standalone), mascotOutfit(night, suit), prop) { rawJson(it) }
    )

    @Test
    fun `a prop alone keeps the day palette`() {
        mascotOutfit(night = false, suit = null) shouldBe null
        val colors = dressedDrink(night = false, suit = null, prop = MascotProp.STEIN)
        colors shouldContainAll listOf(DAY_SUIT, BEER)
        colors shouldNotContainAnyOf listOf(NIGHT_SUIT, COFFEE_CUP)
    }

    @Test
    fun `a prop at night goes on top of the night palette`() {
        val colors = dressedDrink(night = true, suit = null, prop = MascotProp.STEIN)
        colors shouldContainAll listOf(NIGHT_SUIT, BEER)
        colors shouldNotContainAnyOf listOf(DAY_SUIT, COFFEE_CUP)
    }

    @Test
    fun `a suit and a prop both apply`() {
        val colors = dressedDrink(night = false, suit = Color(0xFFB4530F), prop = MascotProp.PUMPKIN)
        colors shouldContainAll listOf(PUMPKIN_SUIT, PUMPKIN_MUG)
        colors shouldNotContainAnyOf listOf(DAY_SUIT, COFFEE_CUP)
    }

    @Test
    fun `without a prop the coffee cup stays`() {
        dressedDrink(night = true, suit = null, prop = null) shouldContainAll listOf(NIGHT_SUIT, COFFEE_CUP)
    }

    @Test
    fun `every occasion brings a prop`() {
        Occasions.Period.entries.forEach { occasion ->
            when (occasion) {
                Occasions.Period.NONE -> MascotProp.forOccasion(occasion) shouldBe null
                else -> MascotProp.forOccasion(occasion) shouldNotBe null
            }
        }
    }

    companion object {
        private const val DAY_SUIT = 0x262626
        private const val NIGHT_SUIT = 0x3d4a63
        private const val PUMPKIN_SUIT = 0xb4530f
        private const val COFFEE_CUP = 0xb6b6b6
        private const val BEER = 0xe9a427
        private const val PUMPKIN_MUG = 0xf07c16
    }
}
