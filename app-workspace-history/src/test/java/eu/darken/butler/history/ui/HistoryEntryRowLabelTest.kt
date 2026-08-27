package eu.darken.butler.history.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * An extraction reports one change per written entry, so the row must label itself from the stored
 * subject instead of the first reported change.
 */
class HistoryEntryRowLabelTest : ComposeTest() {

    private val completedAt = Clock.System.now()

    private fun entry(primaryPath: String?, paths: List<String>) = HistoryEntry(
        id = "entry",
        kind = Operation.Metadata.Kind.EXTRACT,
        intent = null,
        originType = HistoryEntry.OriginType.EXPLORER,
        originWorkspaceId = "ws",
        title = "Extract",
        description = "Extracting",
        summary = null,
        startedAt = completedAt - 1.seconds,
        completedAt = completedAt,
        duration = 1.seconds,
        outcome = HistoryOutcome.COMPLETED,
        errorMessage = null,
        errorClass = null,
        affectedPathsCount = paths.size,
        partialErrorCount = 0,
        pathsTruncated = false,
        paths = paths.map {
            HistoryEntry.PathChange(
                path = it,
                previousPath = null,
                change = Operation.Report.PathChange.Change.ADDED,
            )
        },
        primaryPath = primaryPath,
    )

    private fun render(entry: HistoryEntry) {
        composeTestRule.setContent {
            PreviewWrapper {
                HistoryEntryRow(entry = entry, onClick = {})
            }
        }
    }

    @Test
    fun `the row is labelled with the subject, not the first reported change`() {
        render(
            entry(
                primaryPath = "/sdcard/ButlerQA/backup.zip",
                paths = listOf("/sdcard/ButlerQA/backup/aaa.txt", "/sdcard/ButlerQA/backup/zzz.txt"),
            )
        )

        composeTestRule.onNodeWithText("backup.zip", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("aaa.txt", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a row without a subject still names its first reported change`() {
        render(entry(primaryPath = null, paths = listOf("/sdcard/ButlerQA/backup/aaa.txt")))

        composeTestRule.onNodeWithText("aaa.txt", substring = true).assertIsDisplayed()
    }
}
