package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.archive.ArchiveNotSeekableException
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.BrowsingAbortedException
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import org.junit.Test
import testhelpers.ComposeTest

/** Which errors reach the floating error card, and which ones have their own presentation. */
class ExplorerReadyContentErrorTest : ComposeTest() {

    private fun setContent(state: ExplorerWorkspaceViewModel.State) {
        composeTestRule.setContent {
            PreviewWrapper {
                ExplorerReadyContent(
                    workspaceId = Workspace.Id(),
                    state = state,
                    vm = null,
                    listState = rememberLazyListState(),
                    gridState = rememberLazyGridState(),
                    topBarStackState = rememberFloatingBarStackState(position = BarPosition.TOP),
                    bottomBarStackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM),
                    operationsState = OperationsDisplayState(),
                    clipboardState = ClipboardDisplayState(),
                    isRefreshing = false,
                    pullToRefreshState = rememberPullToRefreshState(),
                    onRefresh = {},
                    initialOperationsExpanded = false,
                    initialClipboardExpanded = false,
                    onShowOperationDetails = {},
                )
            }
        }
    }

    private fun setContent(error: Throwable) = setContent(MockDataProvider.createReadyState().copy(error = error))

    @Test
    fun `an ordinary navigation error raises the error card`() {
        setContent(RuntimeException("nope"))

        composeTestRule.onNodeWithText("Navigation failed").assertIsDisplayed()
    }

    @Test
    fun `an aborted load raises no error card`() {
        setContent(BrowsingAbortedException(ExplorerNavigation.Target.Home))

        composeTestRule.onNodeWithText("Navigation failed").assertDoesNotExist()
    }

    @Test
    fun `a vanished target fills the content area instead of raising an error card`() {
        setContent(MockDataProvider.createErrorState(PathNotFoundException(MISSING_PATH)))
        // The state fades in after a short delay.
        composeTestRule.mainClock.advanceTimeBy(500)

        composeTestRule.onNodeWithText("This folder is gone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Navigation failed").assertDoesNotExist()
    }

    @Test
    fun `an ordinary error without content still raises the error card`() {
        setContent(MockDataProvider.createErrorState(RuntimeException("nope")))
        composeTestRule.mainClock.advanceTimeBy(500)

        composeTestRule.onNodeWithText("Navigation failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("This folder is gone").assertDoesNotExist()
    }

    @Test
    fun `a stream-only archive still raises the archive card`() {
        setContent(MockDataProvider.createErrorState(ArchiveNotSeekableException(ARCHIVE_PATH)))
        composeTestRule.mainClock.advanceTimeBy(500)

        composeTestRule.onNodeWithText("Archive can't be browsed here").assertIsDisplayed()
        composeTestRule.onNodeWithText("This folder is gone").assertDoesNotExist()
    }

    companion object {
        private val MISSING_PATH = LocalPath.build("/data/data/eu.darken.butler")
        private val ARCHIVE_PATH = LocalPath.build("/storage/emulated/0/Download/archive.zip")
    }
}
