package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.BrowsingAbortedException
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import org.junit.Test
import testhelpers.ComposeTest

/** A cancelled load is answered by the aborted dialog in the overlay slot, not by an error card. */
class ExplorerReadyContentErrorTest : ComposeTest() {

    private fun setContent(error: Throwable) {
        composeTestRule.setContent {
            PreviewWrapper {
                ExplorerReadyContent(
                    workspaceId = Workspace.Id(),
                    state = MockDataProvider.createReadyState().copy(error = error),
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
}
