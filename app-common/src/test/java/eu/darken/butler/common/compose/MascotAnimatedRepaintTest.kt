package eu.darken.butler.common.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.model.LottieCompositionCache
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeState
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * A clip carries its colors baked in, so the recolored json is parsed once and cached. An animated
 * mascot that is already on screen when the suit color changes has to go back for the new parse
 * instead of staying on the cached old one for the rest of its life.
 */
@Config(qualifiers = "w400dp-h800dp")
class MascotAnimatedRepaintTest : ComposeTest() {

    @After
    fun tearDown() = LottieCompositionCache.getInstance().clear()

    @Test
    fun `changing the suit color reloads a mounted clip`() {
        LottieCompositionCache.getInstance().clear()
        val suit = mutableStateOf<Color?>(null)

        composeTestRule.setContent {
            PreviewWrapper(theme = ThemeState(mode = ThemeMode.DARK)) {
                CompositionLocalProvider(LocalMascotSuitColor provides suit.value) {
                    ButlerMascot(
                        modifier = Modifier.size(96.dp),
                        variant = ButlerMascotMode.Animated.Wink(loop = false),
                    )
                }
            }
        }

        composeTestRule.waitUntil(TIMEOUT) {
            LottieCompositionCache.getInstance().get("mascot_lottie_wink_night") != null
        }

        suit.value = Color(0xFF3D4A63)

        composeTestRule.waitUntil(TIMEOUT) {
            LottieCompositionCache.getInstance().get("mascot_lottie_wink_night_3d4a63") != null
        }
    }

    companion object {
        private const val TIMEOUT = 10_000L
    }
}
