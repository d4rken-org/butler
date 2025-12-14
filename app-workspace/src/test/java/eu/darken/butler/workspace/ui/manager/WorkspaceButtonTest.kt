package eu.darken.butler.workspace.ui.manager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import org.junit.Test
import testhelpers.ComposeTest

class WorkspaceButtonTest : ComposeTest() {

    @Test
    fun `displays workspace count badge`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceButton(
                    state = WorkspaceButtonViewModel.State(
                        workspaceCount = 5,
                        operationsCount = 0,
                        attentionCount = 0,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun `displays operations count badge`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceButton(
                    state = WorkspaceButtonViewModel.State(
                        workspaceCount = 1,
                        operationsCount = 3,
                        attentionCount = 0,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `displays attention count badge`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceButton(
                    state = WorkspaceButtonViewModel.State(
                        workspaceCount = 1,
                        operationsCount = 0,
                        attentionCount = 2,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun `workspace count over 9 shows 9+`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceButton(
                    state = WorkspaceButtonViewModel.State(
                        workspaceCount = 12,
                        operationsCount = 0,
                        attentionCount = 0,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("9+").assertIsDisplayed()
    }

    @Test
    fun `operations count over 9 shows 9+`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceButton(
                    state = WorkspaceButtonViewModel.State(
                        workspaceCount = 1,
                        operationsCount = 15,
                        attentionCount = 0,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("9+").assertIsDisplayed()
    }

    @Test
    fun `attention count over 9 shows 9+`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceButton(
                    state = WorkspaceButtonViewModel.State(
                        workspaceCount = 1,
                        operationsCount = 0,
                        attentionCount = 10,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("9+").assertIsDisplayed()
    }

    @Test
    fun `all badges display with correct counts`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceButton(
                    state = WorkspaceButtonViewModel.State(
                        workspaceCount = 5,
                        operationsCount = 7,
                        attentionCount = 3,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("5").assertIsDisplayed()
        composeTestRule.onNodeWithText("7").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `zero counts hide badges`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceButton(
                    state = WorkspaceButtonViewModel.State(
                        workspaceCount = 0,
                        operationsCount = 0,
                        attentionCount = 0,
                    ),
                )
            }
        }

        // Only "9+" badge text patterns should not exist
        composeTestRule.onNodeWithText("0").assertDoesNotExist()
    }
}
