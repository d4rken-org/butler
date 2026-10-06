package eu.darken.butler.common.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.model.LottieCompositionCache
import eu.darken.butler.common.Occasions.Period
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class MascotOccasionTest : ComposeTest() {

    @Before
    fun setup() = LottieCompositionCache.getInstance().clear()

    @After
    fun tearDown() = LottieCompositionCache.getInstance().clear()

    private fun showDrink(occasion: Period, mode: ThemeMode = ThemeMode.LIGHT, suit: Color? = null) =
        showDrink(mutableStateOf(occasion), mode, suit)

    private fun showDrink(occasion: State<Period>, mode: ThemeMode, suit: Color? = null) {
        composeTestRule.setContent {
            PreviewWrapper(theme = ThemeState(mode = mode)) {
                CompositionLocalProvider(
                    LocalMascotOccasion provides occasion.value,
                    LocalMascotSuitColor provides suit,
                ) {
                    ButlerMascot(
                        modifier = Modifier.size(96.dp),
                        variant = ButlerMascotMode.Animated.Drink(loop = false, standalone = true),
                    )
                }
            }
        }
    }

    private fun awaitCached(key: String): LottieComposition {
        composeTestRule.waitUntil(TIMEOUT) { LottieCompositionCache.getInstance().get(key) != null }
        return LottieCompositionCache.getInstance().get(key)!!
    }

    @Test
    fun `a prop alone loads on the day palette`() {
        showDrink(Period.OKTOBERFEST)
        awaitCached("mascot_lottie_drink_standalone_day_stein")
    }

    @Test
    fun `a prop at night loads on the night palette`() {
        showDrink(Period.OKTOBERFEST, mode = ThemeMode.DARK)
        awaitCached("mascot_lottie_drink_standalone_night_stein")
    }

    @Test
    fun `halloween dresses him in orange`() {
        showDrink(Period.HALLOWEEN)
        awaitCached("mascot_lottie_drink_standalone_day_b4530f_pumpkin")
    }

    @Test
    fun `a picked suit wins over the occasion's`() {
        showDrink(Period.HALLOWEEN, suit = Color(0xFF3D4A63))
        awaitCached("mascot_lottie_drink_standalone_day_3d4a63_pumpkin")
    }

    @Test
    fun `cold props leave the steam out`() {
        showDrink(Period.OKTOBERFEST)
        val steamless = awaitCached("mascot_lottie_drink_standalone_day_stein")
        steamless.layers.none { it.name in MascotProp.STEAM_LAYERS } shouldBe true
    }

    @Test
    fun `a mounted mascot follows the occasion`() {
        val occasion = mutableStateOf(Period.NONE)
        showDrink(occasion, mode = ThemeMode.DARK)
        awaitCached("mascot_lottie_drink_standalone_night")

        occasion.value = Period.OKTOBERFEST
        awaitCached("mascot_lottie_drink_standalone_night_stein")

        occasion.value = Period.HALLOWEEN
        awaitCached("mascot_lottie_drink_standalone_night_b4530f_pumpkin")
    }

    @Test
    fun `the settings default follows the occasion`() {
        var halloween: Color? = null
        var none: Color? = null
        composeTestRule.setContent {
            PreviewWrapper(theme = ThemeState(mode = ThemeMode.LIGHT)) {
                CompositionLocalProvider(LocalMascotOccasion provides Period.HALLOWEEN) {
                    halloween = mascotDefaultSuitColor()
                }
                none = mascotDefaultSuitColor()
            }
        }
        composeTestRule.waitForIdle()

        halloween shouldBe Color(0xFFB4530F)
        none shouldBe Color(0xFF262626)
    }

    companion object {
        private const val TIMEOUT = 10_000L
    }
}
