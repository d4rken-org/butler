package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class WorkspaceStatusCardTest : ComposeTest() {

    @Test
    fun `displays workspace count`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 0,
                    attentionCount = 0,
                )
            }
        }

        composeTestRule.onNodeWithText("5", substring = true).assertIsDisplayed()
    }

    @Test
    fun `displays operations count`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 1,
                    operationsCount = 3,
                    attentionCount = 0,
                )
            }
        }

        composeTestRule.onNodeWithText("3", substring = true).assertIsDisplayed()
    }

    @Test
    fun `displays attention count`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 1,
                    operationsCount = 0,
                    attentionCount = 2,
                )
            }
        }

        composeTestRule.onNodeWithText("2", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tabs click callback is invoked`() {
        var clicked = false

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 0,
                    attentionCount = 0,
                    onTabsClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Select all tabs").performClick()

        clicked shouldBe true
    }

    @Test
    fun `the tabs chip reports the selection while selecting`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 0,
                    attentionCount = 0,
                    selectedCount = 2,
                )
            }
        }

        composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Select all tabs").assertCountEquals(0)
    }

    /**
     * Clearing hangs off the trailing X alone; the chip body carries no click action of its own.
     * Wiring the body to it - as WorkspaceInfoBar's InfoChip does - would wipe a hand-picked
     * selection on the tap that was only meant to read the count.
     */
    @Test
    fun `only the selection chip's remove affordance clears`() {
        var cleared = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 0,
                    attentionCount = 0,
                    selectedCount = 2,
                    onClearSelection = { cleared++ },
                )
            }
        }

        val chip = composeTestRule.onNodeWithContentDescription("Clear selection")
        chip.assertHasNoClickAction()

        chip.onChildren().filterToOne(hasClickAction()).performClick()

        cleared shouldBe 1
    }

    /**
     * A filter toggled mid-selection would hide checked cards while the count and the batch actions
     * keep including them.
     */
    @Test
    fun `filter chips are inert while selecting`() {
        var operations = 0
        var attention = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 3,
                    attentionCount = 2,
                    selectedCount = 2,
                    onOperationsClick = { operations++ },
                    onAttentionClick = { attention++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Filter by tabs with operations").performClick()
        composeTestRule.onNodeWithContentDescription("Filter by tabs needing attention").performClick()

        operations shouldBe 0
        attention shouldBe 0
    }

    @Test
    fun `operations click callback is invoked`() {
        var clicked = false

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 1,
                    operationsCount = 3,
                    attentionCount = 0,
                    onOperationsClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Filter by tabs with operations").performClick()

        clicked shouldBe true
    }

    @Test
    fun `attention click callback is invoked`() {
        var clicked = false

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 1,
                    operationsCount = 0,
                    attentionCount = 2,
                    onAttentionClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Filter by tabs needing attention").performClick()

        clicked shouldBe true
    }
}
