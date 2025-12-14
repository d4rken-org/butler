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
 * - Text measurement is incorrect (fixed ~20px height, 1px width per character)
 *
 * Use for testing component behavior, clicks, and content - not visual appearance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
abstract class ComposeTest : BaseTest() {

    @get:Rule
    val composeTestRule = createComposeRule()
}
