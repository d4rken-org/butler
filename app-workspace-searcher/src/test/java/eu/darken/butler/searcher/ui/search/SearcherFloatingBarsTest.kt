package eu.darken.butler.searcher.ui.search

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock
import eu.darken.butler.workspace.R as WorkspaceR

/**
 * The searcher's bottom bars have to react to clipboard and operation updates that arrive while the
 * page is already composed - a copy in the searcher is exactly that.
 *
 * The updates are emitted after the first composition on purpose: it also covers the variant where
 * the collect seeds its initial value from a StateFlow, as the main state does.
 */
class SearcherFloatingBarsTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val stateSource = MutableStateFlow(SearcherMockDataProvider.createMockResultsState())
    private val clipboardSource = MutableStateFlow<ClipboardDisplayState?>(null)
    private val operationsSource = MutableStateFlow<OperationsDisplayState?>(null)

    private val pasteLabel: String
        get() = context.getString(WorkspaceR.string.clipboard_open_in_explorer)

    private fun setContent() {
        composeTestRule.setContent {
            PreviewWrapper {
                SearcherWorkspacePage(
                    workspaceId = Workspace.Id(),
                    // Split-pane layout: keeps the mascot-bearing workspace button, which
                    // Robolectric cannot rasterise, out of the toolbar cutout.
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    stateSource = stateSource,
                    clipboardStateSource = clipboardSource,
                    operationsStateSource = operationsSource,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun scrollResults() {
        composeTestRule.onRoot().performTouchInput { swipeUp(startY = centerY, endY = top) }
        composeTestRule.waitForIdle()
    }

    private fun clip() = ClipboardClip.Paths(
        origin = Workspace.Id(),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Documents/report.pdf"),
                fileType = FileType.FILE,
                size = null,
                modifiedAt = null,
            ),
        ),
    )

    @Test
    fun `clipboard bar appears when a clip arrives after first composition`() {
        setContent()

        composeTestRule.onNodeWithContentDescription(pasteLabel).assertDoesNotExist()

        clipboardSource.value = ClipboardDisplayState(entries = listOf(clip()))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(pasteLabel).assertIsDisplayed()
    }

    @Test
    fun `operations bar appears when an operation arrives after first composition`() {
        setContent()

        composeTestRule.onNodeWithText(OPERATION_TITLE).assertDoesNotExist()

        operationsSource.value = OperationsDisplayState(
            operations = listOf(SearcherMockDataProvider.createMockRunningOperation(title = OPERATION_TITLE)),
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(OPERATION_TITLE).assertIsDisplayed()
    }

    @Test
    fun `operations bar stays pinned while an operation is active`() {
        setContent()

        val operation = SearcherMockDataProvider.createMockRunningOperation(title = OPERATION_TITLE)
        operationsSource.value = OperationsDisplayState(operations = listOf(operation))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(OPERATION_TITLE).assertIsDisplayed()

        scrollResults()
        composeTestRule.onNodeWithText(OPERATION_TITLE).assertIsDisplayed()

        operationsSource.value = OperationsDisplayState(
            operations = listOf(
                operation.copy(
                    state = OperationDisplay.State.Cancelled(
                        completedAt = Clock.System.now(),
                        report = null,
                    ),
                ),
            ),
        )
        composeTestRule.waitForIdle()

        scrollResults()
        composeTestRule.onNodeWithText(OPERATION_TITLE).assertIsNotDisplayed()
    }

    companion object {
        private const val OPERATION_TITLE = "Deleting files"
    }
}
