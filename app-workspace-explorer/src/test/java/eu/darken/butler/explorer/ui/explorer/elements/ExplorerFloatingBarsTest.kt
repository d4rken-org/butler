package eu.darken.butler.explorer.ui.explorer.elements

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.ui.explorer.ExplorerBarKeys
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import eu.darken.butler.workspace.R as WorkspaceR

/**
 * Guards the call site rather than the wrappers: a wrong bar key, a `when` branch pointed at the
 * wrong handler or a dropped `initialExpanded` are invisible to the shared wrapper tests.
 */
@Config(qualifiers = "w400dp-h800dp")
class ExplorerFloatingBarsTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val state = MockDataProvider.createStateWithSelection()
    private val detailsShown = mutableListOf<Operation.Id>()
    private lateinit var stackState: FloatingBarStackState

    private val pasteLabel: String
        get() = context.getString(WorkspaceR.string.clipboard_paste)
    private val clipboardHeaderTitle: String
        get() = context.getString(WorkspaceR.string.clipboard_header_title)

    private fun clip(name: String) = MockDataProvider.createMockClipboardCopy(
        files = listOf(name),
        basePath = CLIP_DIR,
    )

    private fun setBars(
        initialOperationsExpanded: Boolean = false,
        initialClipboardExpanded: Boolean = false,
        clipboardState: () -> ClipboardDisplayState = { ClipboardDisplayState(entries = listOf(clip(LATEST_CLIP))) },
        operationsState: () -> OperationsDisplayState,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                stackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM)
                FloatingBarStack(
                    position = BarPosition.BOTTOM,
                    state = stackState,
                ) {
                    ExplorerBottomBars(
                        state = state,
                        vm = null,
                        operationsState = operationsState(),
                        clipboardState = clipboardState(),
                        initialOperationsExpanded = initialOperationsExpanded,
                        initialClipboardExpanded = initialClipboardExpanded,
                        onShowOperationDetails = { detailsShown.add(it) },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the operations and clipboard bars both render`() {
        setBars { OperationsDisplayState(operations = listOf(MockDataProvider.createMockRunningOperation(title = RUNNING_TITLE))) }

        composeTestRule.onNodeWithText(RUNNING_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(pasteLabel).assertIsDisplayed()
    }

    /**
     * `collapseTargets` holds every registered non-Static bar, and bars register regardless of their
     * visibility. The running case is what binds a key to a specific bar: only the operations bar
     * changes scroll behaviour with operation state, so a swap of the two keys shows up there.
     */
    @Test
    fun `each bar registers under the key its workspace persists`() {
        var operations by mutableStateOf(
            OperationsDisplayState(operations = listOf(MockDataProvider.createMockCompletedOperation()))
        )
        setBars { operations }

        composeTestRule.runOnIdle {
            stackState.collapseTargets.keys shouldBe setOf(
                ExplorerBarKeys.OPERATIONS,
                ExplorerBarKeys.CLIPBOARD,
                ExplorerBarKeys.ACTIONS,
            )
        }

        operations = OperationsDisplayState(operations = listOf(MockDataProvider.createMockRunningOperation()))
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            stackState.collapseTargets.keys shouldBe setOf(
                ExplorerBarKeys.CLIPBOARD,
                ExplorerBarKeys.ACTIONS,
            )
        }
    }

    /** `onShowOperationDetails` is a plain parameter, so it stays observable with a null ViewModel. */
    @Test
    fun `clicking a finished operation reaches the details handler`() {
        val completed = MockDataProvider.createMockCompletedOperation(title = COMPLETED_TITLE)
        setBars { OperationsDisplayState(operations = listOf(completed)) }

        composeTestRule.onNodeWithText(COMPLETED_TITLE).performClick()

        detailsShown shouldBe listOf(completed.id)
    }

    /**
     * The two flags are same-typed and adjacent, so both must be asserted from a state where only
     * one of them is set. Separate compositions per case: both bars seed their expansion from an
     * unkeyed `remember`, which no longer follows the parameter once composed.
     */
    @Test
    fun `only the operations bar expands when only its flag is set`() {
        setBars(
            initialOperationsExpanded = true,
            initialClipboardExpanded = false,
            clipboardState = { ClipboardDisplayState(entries = listOf(clip(LATEST_CLIP), clip(OLDER_CLIP))) },
            operationsState = { twoCompletedOperations() },
        )

        composeTestRule.onNodeWithText(COMPLETED_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText("$CLIP_DIR/$OLDER_CLIP").assertDoesNotExist()
    }

    @Test
    fun `only the clipboard bar expands when only its flag is set`() {
        setBars(
            initialOperationsExpanded = false,
            initialClipboardExpanded = true,
            clipboardState = { ClipboardDisplayState(entries = listOf(clip(LATEST_CLIP), clip(OLDER_CLIP))) },
            operationsState = { twoCompletedOperations() },
        )

        composeTestRule.onNodeWithText(COMPLETED_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithText("$CLIP_DIR/$OLDER_CLIP").assertIsDisplayed()
    }

    /**
     * For a BOTTOM stack the first-declared bar is furthest from the screen edge, so the declared
     * order has to read top-to-bottom. Anchored on each bar's outermost row: the operations bar's
     * last row against the clipboard bar's header, and the clipboard bar's row against the actions.
     */
    @Test
    fun `the bars stack in declaration order`() {
        setBars { OperationsDisplayState(operations = listOf(MockDataProvider.createMockRunningOperation(title = RUNNING_TITLE))) }

        val actionLabel = state.availableActions.first { it.isVisible && !it.forceOverflow }.label.get(context)

        val operationsRow = composeTestRule.onNodeWithText(RUNNING_TITLE).getUnclippedBoundsInRoot()
        val clipboardHeader = composeTestRule.onNodeWithText(clipboardHeaderTitle).getUnclippedBoundsInRoot()
        val clipboardRow = composeTestRule.onNodeWithText("$CLIP_DIR/$LATEST_CLIP").getUnclippedBoundsInRoot()
        val actionsBar = composeTestRule.onNodeWithContentDescription(actionLabel).getUnclippedBoundsInRoot()

        withClue("operations bar sits above the clipboard bar") {
            (operationsRow.bottom <= clipboardHeader.top) shouldBe true
        }
        withClue("clipboard bar sits above the actions bar") {
            (clipboardRow.bottom <= actionsBar.top) shouldBe true
        }
    }

    private fun twoCompletedOperations() = OperationsDisplayState(
        operations = listOf(
            MockDataProvider.createMockCompletedOperation(title = COMPLETED_TITLE, minutesAgo = 9),
            MockDataProvider.createMockCompletedOperation(title = NEWEST_TITLE, minutesAgo = 1),
        ),
    )

    companion object {
        private const val RUNNING_TITLE = "Deleting files"
        private const val COMPLETED_TITLE = "Copy operation"
        private const val NEWEST_TITLE = "Archive created"
        private const val CLIP_DIR = "/storage/emulated/0/Clips"
        private const val LATEST_CLIP = "latest.txt"
        private const val OLDER_CLIP = "older.txt"
    }
}
