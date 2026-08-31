package eu.darken.butler.common.theming

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Robolectric has no native bitmap and `captureToImage()` deadlocks, so the provided colour is read
 * out of the composition instead of off a pixel.
 */
class ButlerRootSurfaceTest : ComposeTest() {

    private var contentColor: Color? = null
    private var onBackground: Color? = null
    private var onSurface: Color? = null

    @Composable
    private fun ColorProbe(theme: ThemeState) {
        ButlerTheme(state = theme) {
            ButlerRootSurface {
                contentColor = LocalContentColor.current
                onBackground = MaterialTheme.colorScheme.onBackground
                onSurface = MaterialTheme.colorScheme.onSurface
            }
        }
    }

    @Test
    fun `dark theme content color is onBackground, not the black fallback`() {
        composeTestRule.setContent {
            ColorProbe(ThemeState(mode = ThemeMode.DARK))
        }

        composeTestRule.runOnIdle {
            contentColor shouldNotBe null
            contentColor shouldBe onBackground
            // Dark-only: black is the material3 sentinel and invisible here, light onBackground may be near-black.
            contentColor shouldNotBe Color.Black
        }
    }

    @Test
    fun `light theme content color is onBackground`() {
        composeTestRule.setContent {
            ColorProbe(ThemeState(mode = ThemeMode.LIGHT))
        }

        composeTestRule.runOnIdle {
            contentColor shouldNotBe null
            contentColor shouldBe onBackground
        }
    }

    @Test
    fun `onBackground wins over onSurface where the two schemes diverge`() {
        composeTestRule.setContent {
            ColorProbe(
                ThemeState(
                    mode = ThemeMode.DARK,
                    style = ThemeStyle.HIGH_CONTRAST,
                    color = ThemeColor.AMOLED,
                ),
            )
        }

        composeTestRule.runOnIdle {
            contentColor shouldBe onBackground
            contentColor shouldNotBe onSurface
        }
    }
}
