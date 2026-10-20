package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.apps.ui.apps.AppsPageAction
import eu.darken.butler.apps.ui.apps.AppsWorkspaceViewModel
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import java.io.IOException

class AppsReadyContentErrorTest : ComposeTest() {

    private val errorState = AppsWorkspaceViewModel.State.Ready(
        apps = emptyList(),
        isLoading = false,
        error = IOException("reload failed"),
    )

    private fun setContent(
        state: () -> AppsWorkspaceViewModel.State.Ready,
        onPageAction: (AppsPageAction) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                AppsReadyContent(
                    state = state(),
                    listState = rememberLazyListState(),
                    gridState = rememberLazyGridState(),
                    topBarStackState = rememberFloatingBarStackState(position = BarPosition.TOP),
                    bottomBarStackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM),
                    onPageAction = onPageAction,
                )
            }
        }
    }

    @Test
    fun `list mode shows the error card instead of the empty content`() {
        setContent(state = { errorState })

        composeTestRule.onNodeWithText("Could not load app data").assertIsDisplayed()
        composeTestRule.onNodeWithText("No apps found").assertDoesNotExist()
    }

    @Test
    fun `grid mode shows the error card instead of the empty content`() {
        val gridErrorState = errorState.copy(viewStyle = AppsViewStyle(mode = AppsViewStyle.Mode.GRID))
        setContent(state = { gridErrorState })

        composeTestRule.onNodeWithText("Could not load app data").assertIsDisplayed()
        composeTestRule.onNodeWithText("No apps found").assertDoesNotExist()
    }

    @Test
    fun `retry on the error card requests a refresh`() {
        val actions = mutableListOf<AppsPageAction>()
        setContent(state = { errorState }, onPageAction = { actions.add(it) })

        composeTestRule.onNodeWithText("Retry").performClick()

        actions shouldBe listOf(AppsPageAction.Apps.Refresh)
    }

    @Test
    fun `error card is replaced by the app list once a reload succeeds`() {
        var state by mutableStateOf(errorState)
        setContent(state = { state })

        composeTestRule.onNodeWithText("Could not load app data").assertIsDisplayed()

        state = AppsWorkspaceViewModel.State.Ready(
            apps = listOf(AppsMockDataProvider.Presets.chromeItem),
            isLoading = false,
            error = null,
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Could not load app data").assertDoesNotExist()
        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
    }
}
