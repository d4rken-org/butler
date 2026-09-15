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
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The saturation/brightness square is a bare Canvas with a drag listener, which exposes nothing to
 * TalkBack or a keyboard. The three sliders are the only way to reach a color without a pointer, so
 * they are driven here through their semantics action rather than by a simulated drag.
 */
@Config(qualifiers = "w400dp-h800dp")
class MascotSuitColorDialogTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hueLabel = context.getString(R.string.ui_mascot_suit_hue_label)
    private val saturationLabel = context.getString(R.string.ui_mascot_suit_saturation_label)
    private val brightnessLabel = context.getString(R.string.ui_mascot_suit_brightness_label)
    private val defaultAction = context.getString(R.string.ui_mascot_suit_default_action)
    private val applyAction = context.getString(R.string.general_apply_action)
    private val cancelAction = context.getString(R.string.general_cancel_action)
    private val mascotDescription = context.getString(R.string.butler_mascot_description)

    private var selected: Int? = null
    private var selections = 0
    private var dismissals = 0

    private fun setDialog(suitColor: Int? = null) {
        composeTestRule.setContent {
            PreviewWrapper {
                MascotSuitColorDialog(
                    suitColor = suitColor,
                    onColorSelected = {
                        selected = it
                        selections++
                    },
                    onDismiss = { dismissals++ },
                )
            }
        }
    }

    private fun setChannel(label: String, value: Float) = composeTestRule
        .onNodeWithContentDescription(label)
        .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }

    /** Pure green, so no rounding can put the readout on a neighboring hex. */
    private fun pickGreen() {
        setChannel(hueLabel, 120f)
        setChannel(saturationLabel, 1f)
        setChannel(brightnessLabel, 1f)
    }

    @Test
    fun `the dialog previews the pick on Butler himself`() {
        setDialog()

        composeTestRule.onNodeWithContentDescription(mascotDescription).assertExists()
    }

    @Test
    fun `each slider moves the previewed color`() {
        setDialog()
        pickGreen()

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.ui_mascot_suit_value_description, "#00FF00"))
            .assertExists()
    }

    @Test
    fun `applying emits the picked color`() {
        setDialog()
        pickGreen()

        composeTestRule.onNodeWithText(applyAction).performClick()

        selected shouldBe 0x00FF00
        selections shouldBe 1
    }

    @Test
    fun `the default action clears the override`() {
        setDialog(suitColor = 0x8C1C13)

        composeTestRule.onNodeWithText(defaultAction).performClick()

        selected shouldBe null
        selections shouldBe 1
    }

    @Test
    fun `cancelling emits nothing`() {
        setDialog(suitColor = 0x8C1C13)
        pickGreen()

        composeTestRule.onNodeWithText(cancelAction).performClick()

        selections shouldBe 0
        dismissals shouldBe 1
    }
}
