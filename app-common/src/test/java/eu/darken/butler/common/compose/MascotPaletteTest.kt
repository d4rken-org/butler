package eu.darken.butler.common.compose

import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.R
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import java.io.File

/**
 * The mascot is fixed artwork spread over two file formats: vector drawables that resolve
 * `@color/mascot_*` per theme, and Lottie clips that carry the same colors as baked-in literals for
 * [MascotPalette] to swap. Nothing in either format states which body part a color belongs to, so a
 * re-export from After Effects can silently put the jacket and the moustache back on one value and
 * undo the dark theme. These tests are what makes that loud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class MascotPaletteTest : BaseTest() {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val butlerClips = mapOf(
        "wink" to R.raw.mascot_lottie_wink,
        "greeting" to R.raw.mascot_lottie_greeting,
        "hatoff" to R.raw.mascot_lottie_hatoff,
        "drink" to R.raw.mascot_lottie_drink,
        "drink_standalone" to R.raw.mascot_lottie_drink_standalone,
        "moustache_stroke" to R.raw.mascot_lottie_moustache_stroke,
        "sleep_sleeping" to R.raw.mascot_lottie_sleep_sleeping,
        "sleep_snoring" to R.raw.mascot_lottie_sleep_snoring,
        "sleep_waking" to R.raw.mascot_lottie_sleep_waking,
    )

    // Every color the butler is drawn from. Props (the coffee cup) sit outside the outfit.
    private val knownPalette = setOf(
        0x212121, // ink: the moustache, and ko's crossed-out eyes
        0x262626, // suit
        0x1e1e1e, // trousers
        0x3f3f3f, // lapel shadow
        0x565656, // lapel edge, left
        0x666666, // lapel edge, right
        0x9b9a9a, // cuffs
        0xf2f2f2, // collar
        0xffffff, // shirt, eyes, pocket square
        0x48ff80, // head
        0xb6b6b6, // saucer
        0x707070, // coffee
    )

    // Drawable to the number of ink fills it carries: the moustache everywhere, plus ko's four
    // crossed-out eye strokes.
    private val mascotDrawables = mapOf(
        "mascot_normal" to 1,
        "mascot_happy" to 1,
        "mascot_sad" to 1,
        "mascot_ko" to 5,
    )

    // The "c" key is what makes it a fill or stroke color rather than an anchor that happens to be
    // three numbers.
    private fun colorList(json: String): List<Int> =
        Regex(""""c":\s*\{\s*"a":\s*0,\s*"k":\s*\[([0-9.,\s]+)]""")
            .findAll(json)
            .mapNotNull { match ->
                val channels = match.groupValues[1].split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (channels.size < 3) return@mapNotNull null
                channels.take(3).fold(0) { acc, channel -> (acc shl 8) or Math.round(channel * 255) }
            }
            .toList()

    private fun colorsIn(json: String): Set<Int> = colorList(json).toSet()

    private fun colorCounts(json: String): Map<Int, Int> = colorList(json).groupingBy { it }.eachCount()

    private fun Int.asHex(): String = "#%06x".format(this)

    private fun rawJson(resId: Int): String =
        context.resources.openRawResource(resId).bufferedReader().use { it.readText() }

    private fun clipColors(resId: Int): Set<Int> = colorsIn(rawJson(resId))

    @Test
    fun `clips only use colors the palette knows`() {
        butlerClips.forEach { (name, resId) ->
            withClue(name) { (clipColors(resId) - knownPalette) shouldBe emptySet() }
        }
    }

    // He wears the same outfit in every clip: 1 ink (the moustache), 5 suit (jacket, two upper
    // arms, hat and tie), 2 trousers, and the lapel work. Counts rather than presence, so moving a
    // single fill from ink back to suit shows up.
    private val outfitFills = mapOf(
        0x212121 to 1,
        0x262626 to 5,
        0x1e1e1e to 2,
        0x3f3f3f to 2,
        0x565656 to 1,
        0x666666 to 1,
        0x9b9a9a to 2,
    )

    @Test
    fun `clips keep the ink separate from the suit`() {
        butlerClips.forEach { (name, resId) ->
            val counted = colorCounts(rawJson(resId))
            outfitFills.forEach { (color, expected) ->
                withClue("$name has ${counted[color] ?: 0} fills of ${color.asHex()}, expected $expected") {
                    (counted[color] ?: 0) shouldBe expected
                }
            }
        }
    }

    @Test
    fun `clips carry no color the recolor would skip`() {
        butlerClips.forEach { (name, resId) ->
            val json = rawJson(resId)
            withClue("$name has an animated color, which stays on the day palette") {
                Regex(""""c":\s*\{\s*"a":\s*1""").containsMatchIn(json) shouldBe false
            }
            withClue("$name has a gradient, which stays on the day palette") {
                Regex(""""ty":\s*"g[fs]"""").containsMatchIn(json) shouldBe false
            }
        }
    }

    @Test
    fun `night map has no entry the artwork does not use`() {
        val everyColor = butlerClips.values.flatMap { clipColors(it) }.toSet()
        MascotPalette.NIGHT.keys.filterNot { everyColor.contains(it) } shouldContainExactly emptyList()
    }

    @Test
    fun `forNight repaints the suit and leaves the ink alone`() {
        val colors = colorsIn(MascotPalette.forNight(rawJson(R.raw.mascot_lottie_wink)))

        colors.contains(0x6e6e6e) shouldBe true   // the suit lightened
        colors.contains(0x262626) shouldBe false  // and none of it was left behind
        colors.contains(0x212121) shouldBe true   // the moustache held
        colors.contains(0x48ff80) shouldBe true   // so did the head
    }

    @Test
    fun `drawables take every fill from a color resource`() {
        mascotDrawables.keys.forEach { name ->
            val xml = drawableSource(name).readText()
            withClue("$name has a hardcoded fill, so it cannot follow the theme") {
                Regex("""fillColor="#""").containsMatchIn(xml) shouldBe false
            }
        }
    }

    @Test
    fun `drawables wear the same outfit as the clips`() {
        mascotDrawables.forEach { (name, inkFills) ->
            val xml = drawableSource(name).readText()
            val counted = Regex("""fillColor="@color/(mascot_\w+)"""")
                .findAll(xml)
                .map { it.groupValues[1] }
                .groupingBy { it }
                .eachCount()

            // ko wears the same outfit but takes four extra ink fills for his crossed-out eyes
            withClue("$name uses ${counted["mascot_ink"] ?: 0} ink fills, expected $inkFills") {
                (counted["mascot_ink"] ?: 0) shouldBe inkFills
            }
            mapOf(
                "mascot_suit" to 5, "mascot_trousers" to 2, "mascot_lapel_shadow" to 2,
                "mascot_lapel_edge_left" to 1, "mascot_lapel_edge_right" to 1, "mascot_cuffs" to 2,
            ).forEach { (resource, expected) ->
                withClue("$name uses ${counted[resource] ?: 0} of $resource, expected $expected") {
                    (counted[resource] ?: 0) shouldBe expected
                }
            }
        }
    }

    @Test
    fun `the outfit lightens at night and the rest holds`() {
        val day = paletteColors()
        RuntimeEnvironment.setQualifiers("+night")
        val night = paletteColors()

        MascotPalette.NIGHT.forEach { (dayColor, nightColor) ->
            val resource = day.entries.single { it.value == dayColor }.key
            withClue("$resource") { night.getValue(resource) shouldBe nightColor }
        }
        listOf("mascot_ink", "mascot_offwhite", "mascot_white", "mascot_green").forEach {
            withClue(it) { night.getValue(it) shouldBe day.getValue(it) }
        }
    }

    @Test
    fun `ink and suit are separate values in the drawables too`() {
        val day = paletteColors()
        day.getValue("mascot_ink") shouldNotBe day.getValue("mascot_suit")
    }

    private fun paletteColors(): Map<String, Int> = listOf(
        "mascot_ink", "mascot_suit", "mascot_trousers", "mascot_lapel_shadow",
        "mascot_lapel_edge_left", "mascot_lapel_edge_right", "mascot_cuffs",
        "mascot_offwhite", "mascot_white", "mascot_green",
    ).associateWith { name ->
        val id = context.resources.getIdentifier(name, "color", context.packageName)
        withClue("no @color/$name") { id shouldNotBe 0 }
        context.getColor(id) and 0xFFFFFF
    }

    /** Vector sources are compiled away, so the check has to read them off disk. */
    private fun drawableSource(name: String): File = listOf(
        File("src/main/res/drawable/$name.xml"),
        File("app-common/src/main/res/drawable/$name.xml"),
    ).firstOrNull { it.isFile }
        ?: error("Cannot find $name.xml relative to ${File("").absolutePath}")

    private fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
}
