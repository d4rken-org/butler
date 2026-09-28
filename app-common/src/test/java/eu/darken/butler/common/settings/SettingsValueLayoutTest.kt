package eu.darken.butler.common.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w320dp-h640dp")
class SettingsValueLayoutTest : ComposeTest() {

    @Test
    fun `a long value sits below the subtitle instead of beside the title`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Settings,
                    title = TITLE,
                    subtitle = SUBTITLE,
                    value = VALUE,
                    onClick = {},
                )
            }
        }

        val title = composeTestRule.onNodeWithText(TITLE, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val subtitle = composeTestRule.onNodeWithText(SUBTITLE, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val value = composeTestRule.onNodeWithText(VALUE, useUnmergedTree = true).getUnclippedBoundsInRoot()

        value.left shouldBe title.left
        value.top shouldBeGreaterThanOrEqualTo subtitle.bottom
    }

    @Test
    fun `a gated row with a value keeps the value below the subtitle`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Settings,
                    title = TITLE,
                    subtitle = SUBTITLE,
                    value = VALUE,
                    onClick = {},
                    onUpgrade = {},
                )
            }
        }

        val title = composeTestRule.onNodeWithText(TITLE, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val subtitle = composeTestRule.onNodeWithText(SUBTITLE, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val value = composeTestRule.onNodeWithText(VALUE, useUnmergedTree = true).getUnclippedBoundsInRoot()

        value.left shouldBe title.left
        value.top shouldBeGreaterThanOrEqualTo subtitle.bottom
    }

    companion object {
        private const val TITLE = "Portrait layout mode"
        private const val SUBTITLE = "Choose tab layout in portrait orientation."
        private const val VALUE = "Adaptive (Single with tab rail)"
    }
}
