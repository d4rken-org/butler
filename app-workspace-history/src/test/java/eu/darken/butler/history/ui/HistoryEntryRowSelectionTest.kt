package eu.darken.butler.history.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Selection mode is driven from the row: the long press starts it, a checkbox reports it. */
class HistoryEntryRowSelectionTest : ComposeTest() {

    private val completedAt = Clock.System.now()

    private val entry = HistoryEntry(
        id = "entry",
        kind = Operation.Metadata.Kind.COPY,
        intent = null,
        originType = HistoryEntry.OriginType.EXPLORER,
        originWorkspaceId = "ws",
        title = "Copy",
        description = "Copying",
        summary = null,
        startedAt = completedAt - 1.seconds,
        completedAt = completedAt,
        duration = 1.seconds,
        outcome = HistoryOutcome.COMPLETED,
        errorMessage = null,
        errorClass = null,
        affectedPathsCount = 1,
        partialErrorCount = 0,
        pathsTruncated = false,
        paths = emptyList(),
        primaryPath = "/sdcard/ButlerQA/backup.zip",
    )

    private fun render(
        selectionActive: Boolean = false,
        isSelected: Boolean = false,
        onLongClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                HistoryEntryRow(
                    entry = entry,
                    onClick = {},
                    onLongClick = onLongClick,
                    isSelected = isSelected,
                    selectionActive = selectionActive,
                )
            }
        }
    }

    @Test
    fun `a long press on the row starts the selection`() {
        var longClicks = 0
        render(onLongClick = { longClicks++ })

        composeTestRule.onNodeWithText("Copied  backup.zip").performTouchInput { longClick() }

        longClicks shouldBe 1
    }

    @Test
    fun `the kind icon labels the row while nothing is selected`() {
        render(selectionActive = false)

        composeTestRule.onNodeWithContentDescription("COPY").assertExists()
        composeTestRule.onNodeWithTag(HISTORY_ROW_CHECKBOX_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a checkbox replaces the kind icon in selection mode`() {
        render(selectionActive = true, isSelected = true)

        composeTestRule.onNodeWithTag(HISTORY_ROW_CHECKBOX_TAG, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("COPY").assertDoesNotExist()
    }

    @Test
    fun `the full path gets a line of its own`() {
        render()

        composeTestRule.onNodeWithText("/sdcard/ButlerQA/backup.zip", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
