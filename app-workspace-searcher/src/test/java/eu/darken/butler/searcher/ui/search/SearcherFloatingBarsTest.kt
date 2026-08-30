package eu.darken.butler.searcher.ui.search

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.LocalWorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
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

    private val workspaceId = Workspace.Id()
    private val barCollapseStates = WorkspaceBarCollapseStates()

    private val stateSource = MutableStateFlow(SearcherMockDataProvider.createMockResultsState())
    private val clipboardSource = MutableStateFlow<ClipboardDisplayState?>(null)
    private val operationsSource = MutableStateFlow<OperationsDisplayState?>(null)

    private val pasteLabel: String
        get() = context.getString(WorkspaceR.string.clipboard_open_in_explorer)
    private val clipboardHeaderTitle: String
        get() = context.getString(WorkspaceR.string.clipboard_header_title)

    private fun setContent() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspaceBarCollapseStates provides barCollapseStates) {
                    SearcherWorkspacePage(
                        workspaceId = workspaceId,
                        // Split-pane layout: keeps the mascot-bearing workspace button, which
                        // Robolectric cannot rasterise, out of the toolbar cutout.
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        stateSource = stateSource,
                        clipboardStateSource = clipboardSource,
                        operationsStateSource = operationsSource,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /**
     * The page composes its stacks itself, so the collapse registry it writes its per-bar fractions
     * into is the read-only route to the keys the bars registered under.
     */
    private fun bottomBarKeys(): Set<String> = barCollapseStates
        .snapshot()[workspaceId]
        ?.get(BarPosition.BOTTOM.persistedKey)
        ?.keys
        .orEmpty()

    private fun scrollResults() {
        composeTestRule.onRoot().performTouchInput { swipeUp(startY = centerY, endY = top) }
        composeTestRule.waitForIdle()
    }

    private fun clip() = ClipboardClip.Paths(
        origin = Workspace.Id(),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            LocalPathLookup(
                lookedUp = LocalPath.build(CLIP_PATH),
                fileType = FileType.FILE,
                size = null,
                modifiedAt = null,
            ),
        ),
    )

    private fun completedOperation() = SearcherMockDataProvider
        .createMockRunningOperation(title = OPERATION_TITLE)
        .copy(
            state = OperationDisplay.State.Completed(
                summary = "Done".toCaString(),
                completedAt = Clock.System.now(),
                report = null,
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

    /**
     * `collapseTargets` holds every registered non-Static bar, and bars register regardless of their
     * visibility. The running case is what binds a key to a specific bar: only the operations bar
     * changes scroll behaviour with operation state, so a swap of the two keys shows up there.
     */
    @Test
    fun `each bar registers under the key the searcher persists`() {
        setContent()

        clipboardSource.value = ClipboardDisplayState(entries = listOf(clip()))
        operationsSource.value = OperationsDisplayState(operations = listOf(completedOperation()))
        composeTestRule.waitForIdle()

        bottomBarKeys() shouldBe setOf(
            SearcherBarKeys.OPERATIONS,
            SearcherBarKeys.CLIPBOARD,
            SearcherBarKeys.ACTIONS,
        )

        operationsSource.value = OperationsDisplayState(
            operations = listOf(SearcherMockDataProvider.createMockRunningOperation(title = OPERATION_TITLE)),
        )
        composeTestRule.waitForIdle()

        bottomBarKeys() shouldBe setOf(
            SearcherBarKeys.CLIPBOARD,
            SearcherBarKeys.ACTIONS,
        )
    }

    /**
     * For a BOTTOM stack the first-declared bar is furthest from the screen edge. Anchored on each
     * bar's outermost row, since the bars themselves carry no addressable node.
     */
    @Test
    fun `the bottom bars stack in declaration order`() {
        setContent()

        clipboardSource.value = ClipboardDisplayState(entries = listOf(clip()))
        operationsSource.value = OperationsDisplayState(
            operations = listOf(SearcherMockDataProvider.createMockRunningOperation(title = OPERATION_TITLE)),
        )
        composeTestRule.waitForIdle()

        val actionLabel = stateSource.value.availableActions
            .first { it.isVisible && !it.forceOverflow }
            .label.get(context)

        val operationsRow = composeTestRule.onNodeWithText(OPERATION_TITLE).getUnclippedBoundsInRoot()
        val clipboardHeader = composeTestRule.onNodeWithText(clipboardHeaderTitle).getUnclippedBoundsInRoot()
        val clipboardRow = composeTestRule.onNodeWithText(CLIP_PATH).getUnclippedBoundsInRoot()
        val actionsBar = composeTestRule.onNodeWithContentDescription(actionLabel).getUnclippedBoundsInRoot()

        withClue("operations bar sits above the clipboard bar") {
            (operationsRow.bottom <= clipboardHeader.top) shouldBe true
        }
        withClue("clipboard bar sits above the actions bar") {
            (clipboardRow.bottom <= actionsBar.top) shouldBe true
        }
    }

    companion object {
        private const val OPERATION_TITLE = "Deleting files"
        private const val CLIP_PATH = "/storage/emulated/0/Documents/report.pdf"
    }
}
