package testhelpers

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base class for Compose UI tests running on Robolectric.
 *
 * Known limitations with Robolectric Compose testing:
 * - No native bitmap (ImageBitmap() leads to NullPointerException)
 * - No drawing (captureToImage() deadlocks)
 * - Text measurement is synthetic: about 1px of width per character, and a line height that does
 *   not follow the text style (labelSmall, bodyMedium and titleMedium all measure 36dp).
 *
 * A test whose assertion depends on real font metrics can annotate its class with
 * `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, which measures text through the real font stack
 * (bodyMedium then measures its 20dp line height).
 *
 * Use for testing component behavior, clicks, and content - not visual appearance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
abstract class ComposeTest : BaseTest() {

    @get:Rule
    val composeTestRule = createComposeRule()
}
