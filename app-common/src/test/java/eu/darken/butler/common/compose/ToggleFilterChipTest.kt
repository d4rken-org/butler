package eu.darken.butler.common.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ToggleFilterChipTest : ComposeTest() {

    @Test
    fun `displays label text`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ToggleFilterChip(
                    selected = false,
                    onClick = {},
                    labelRes = android.R.string.ok,
                    iconVector = Icons.TwoTone.CheckCircle,
                    contentDescriptionRes = android.R.string.ok,
                )
            }
        }

        composeTestRule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun `click callback is invoked`() {
        var clicked = false

        composeTestRule.setContent {
            PreviewWrapper {
                ToggleFilterChip(
                    selected = false,
                    onClick = { clicked = true },
                    labelRes = android.R.string.ok,
                    iconVector = Icons.TwoTone.CheckCircle,
                    contentDescriptionRes = android.R.string.ok,
                )
            }
        }

        composeTestRule.onNodeWithText("OK").performClick()

        clicked shouldBe true
    }

    @Test
    fun `selected state is reflected in chip`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ToggleFilterChip(
                    selected = true,
                    onClick = {},
                    labelRes = android.R.string.ok,
                    iconVector = Icons.TwoTone.CheckCircle,
                    contentDescriptionRes = android.R.string.ok,
                )
            }
        }

        composeTestRule.onNodeWithText("OK").assertIsSelected()
    }

    @Test
    fun `unselected state is reflected in chip`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ToggleFilterChip(
                    selected = false,
                    onClick = {},
                    labelRes = android.R.string.ok,
                    iconVector = Icons.TwoTone.CheckCircle,
                    contentDescriptionRes = android.R.string.ok,
                )
            }
        }

        composeTestRule.onNodeWithText("OK").assertIsNotSelected()
    }

    @Test
    fun `content description is set correctly`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ToggleFilterChip(
                    selected = false,
                    onClick = {},
                    labelRes = android.R.string.ok,
                    iconVector = Icons.TwoTone.Folder,
                    contentDescriptionRes = android.R.string.cancel,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
    }

    @Test
    fun `different icon does not affect functionality`() {
        var clicked = false

        composeTestRule.setContent {
            PreviewWrapper {
                ToggleFilterChip(
                    selected = true,
                    onClick = { clicked = true },
                    labelRes = android.R.string.ok,
                    iconVector = Icons.TwoTone.Folder,
                    contentDescriptionRes = android.R.string.ok,
                )
            }
        }

        composeTestRule.onNodeWithText("OK").performClick()

        clicked shouldBe true
    }
}
