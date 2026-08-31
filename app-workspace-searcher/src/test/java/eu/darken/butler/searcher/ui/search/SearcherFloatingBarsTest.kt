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
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
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
import eu.darken.butler.common.R as CommonR
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
    private val foldersLabel: String
        get() = context.resources.getQuantityString(CommonR.plurals.common_folders_count, FOLDER_COUNT, FOLDER_COUNT)
    private val filesLabel: String
        get() = context.resources.getQuantityString(CommonR.plurals.common_files_count, FILE_COUNT, FILE_COUNT)

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
    private fun barKeys(position: BarPosition): Set<String> = barCollapseStates
        .snapshot()[workspaceId]
        ?.get(position.persistedKey)
        ?.keys
        .orEmpty()

    private fun bottomBarKeys(): Set<String> = barKeys(BarPosition.BOTTOM)

    private fun topBarKeys(): Set<String> = barKeys(BarPosition.TOP)

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

    /** A result set whose folder and file counts differ from each other and from the selection. */
    private fun mixedResultsState() = SearcherWorkspaceViewModel.State.Ready(
        filenameQuery = "config",
        workspaceState = SearcherWorkspace.State(
            searchTargets = listOf(SearchTarget.Path.from(LocalPath.build("/storage/emulated/0"))),
            searchStatus = SearcherWorkspace.State.SearchStatus.IDLE,
            results = List(FOLDER_COUNT) { index ->
                SearcherMockDataProvider.createMockDirectory(name = "config-dir-$index")
            } + List(FILE_COUNT) { index ->
                SearcherMockDataProvider.createMockTextFile(name = "config-$index.txt")
            },
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

    /**
     * The info bar's scroll behaviour is Static and so never a collapse target, which is why only
     * the toolbar and the progress card can show up here.
     */
    @Test
    fun `the top bars register under the keys the searcher persists`() {
        setContent()

        topBarKeys() shouldBe setOf(
            SearcherBarKeys.TOOLBAR,
            SearcherBarKeys.PROGRESS,
        )
    }

    /** Pins the folder/file/size aggregates the info bar is fed with. */
    @Test
    fun `the info bar counts folders and files separately`() {
        stateSource.value = mixedResultsState()
        setContent()

        composeTestRule.onNodeWithText(foldersLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(filesLabel).assertIsDisplayed()
    }

    companion object {
        private const val OPERATION_TITLE = "Deleting files"
        private const val CLIP_PATH = "/storage/emulated/0/Documents/report.pdf"
        private const val FOLDER_COUNT = 2
        private const val FILE_COUNT = 3
    }
}
