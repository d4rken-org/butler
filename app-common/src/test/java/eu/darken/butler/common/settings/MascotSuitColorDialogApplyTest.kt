package eu.darken.butler.common.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * Applying without picking anything must not turn the current default into a stored override: the
 * override is one value for both themes while the default is per-theme, so pinning the mode the
 * dialog happened to be opened in shows that mode's suit in the other one too.
 */
@Config(qualifiers = "w400dp-h800dp")
class MascotSuitColorDialogApplyTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hueLabel = context.getString(R.string.ui_mascot_suit_hue_label)
    private val saturationLabel = context.getString(R.string.ui_mascot_suit_saturation_label)
    private val brightnessLabel = context.getString(R.string.ui_mascot_suit_brightness_label)
    private val defaultAction = context.getString(R.string.ui_mascot_suit_default_action)
    private val applyAction = context.getString(R.string.general_apply_action)

    private var selected: Int? = null
    private var selections = 0

    private fun setDialog(suitColor: Int? = null) {
        composeTestRule.setContent {
            PreviewWrapper {
                MascotSuitColorDialog(
                    suitColor = suitColor,
                    onColorSelected = {
                        selected = it
                        selections++
                    },
                    onDismiss = {},
                )
            }
        }
    }

    private fun setChannel(label: String, value: Float) = composeTestRule
        .onNodeWithContentDescription(label)
        .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }

    /** Pure green, so no rounding can put the emitted value on a neighboring hex. */
    private fun pickGreen() {
        setChannel(hueLabel, 120f)
        setChannel(saturationLabel, 1f)
        setChannel(brightnessLabel, 1f)
    }

    @Test
    fun `applying an untouched dialog keeps the override unset`() {
        setDialog(suitColor = null)

        composeTestRule.onNodeWithText(applyAction).performClick()

        selections shouldBe 1
        selected shouldBe null
    }

    @Test
    fun `applying after picking still emits the color`() {
        setDialog(suitColor = null)
        pickGreen()

        composeTestRule.onNodeWithText(applyAction).performClick()

        selections shouldBe 1
        selected shouldNotBe null
        selected shouldBe 0x00FF00
    }

    @Test
    fun `the default action clears the override even without a stored one`() {
        setDialog(suitColor = null)

        composeTestRule.onNodeWithText(defaultAction).performClick()

        selections shouldBe 1
        selected shouldBe null
    }
}
