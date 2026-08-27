package eu.darken.butler.workspace.ui.operations.bar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock

class OperationEntryRowTest : ComposeTest() {

    private fun createOperation(
        title: String = "Test Operation",
        description: String = "Test description",
        state: OperationDisplay.State = OperationDisplay.State.Queued,
    ) = OperationDisplay(
        id = Operation.Id(),
        title = title.toCaString(),
        description = description.toCaString(),
        icon = Icons.TwoTone.Delete,
        state = state,
        startedAt = Clock.System.now(),
    )

    @Test
    fun `displays operation title`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationEntryRow(
                    operation = createOperation(title = "Deleting files"),
                    onRowClick = {},
                    isBarExpanded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Deleting files").assertIsDisplayed()
    }

    @Test
    fun `row click callback is invoked`() {
        var clicked = false

        composeTestRule.setContent {
            PreviewWrapper {
                OperationEntryRow(
                    operation = createOperation(),
                    onRowClick = { clicked = true },
                    isBarExpanded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Test Operation").performClick()

        clicked shouldBe true
    }

    @Test
    fun `running state displays progress text`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationEntryRow(
                    operation = createOperation(
                        state = OperationDisplay.State.Running(
                            primaryProgress = Progress.Data(
                                primary = "Copying files".toCaString(),
                                secondary = "Processing item 3 of 10".toCaString(),
                                count = Progress.Count.Counter(3, 10),
                            )
                        )
                    ),
                    onRowClick = {},
                    isBarExpanded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Processing item 3 of 10").assertIsDisplayed()
    }

    @Test
    fun `completed state displays summary`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationEntryRow(
                    operation = createOperation(
                        state = OperationDisplay.State.Completed(
                            summary = "Deleted 5 items successfully".toCaString(),
                            completedAt = Clock.System.now(),
                            report = object : Operation.Report {
                                override val summary = "Deleted 5 items".toCaString()
                                override val affectedPaths = emptyList<Operation.Report.PathChange>()
                                override val subjectPath = null
                            },
                        )
                    ),
                    onRowClick = {},
                    isBarExpanded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Deleted 5 items successfully").assertIsDisplayed()
    }

    @Test
    fun `failed state displays error summary`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationEntryRow(
                    operation = createOperation(
                        state = OperationDisplay.State.Failed(
                            summary = "Permission denied".toCaString(),
                            completedAt = Clock.System.now(),
                            report = null,
                        )
                    ),
                    onRowClick = {},
                    isBarExpanded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Permission denied").assertIsDisplayed()
    }

    @Test
    fun `waiting state displays reason`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationEntryRow(
                    operation = createOperation(
                        state = OperationDisplay.State.Waiting(
                            reason = "Waiting for user confirmation".toCaString(),
                        )
                    ),
                    onRowClick = {},
                    isBarExpanded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Waiting for user confirmation").assertIsDisplayed()
    }

    @Test
    fun `cancelled state shows in expanded view`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationEntryRow(
                    operation = createOperation(
                        title = "Move operation",
                        state = OperationDisplay.State.Cancelled(
                            completedAt = Clock.System.now(),
                            report = null,
                        )
                    ),
                    onRowClick = {},
                    isBarExpanded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Move operation").assertIsDisplayed()
    }
}
