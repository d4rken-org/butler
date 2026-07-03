package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.ui.test.assertIsDisplayed
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

        composeTestRule.onNodeWithContentDescription("Show all tabs").performClick()

        clicked shouldBe true
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
