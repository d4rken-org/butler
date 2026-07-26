package eu.darken.butler.apps.ui.details.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.core.details.components.ComponentsUiState
import eu.darken.butler.apps.core.details.components.filter
import eu.darken.butler.common.compose.PreviewWrapper
import org.junit.Test
import testhelpers.ComposeTest

class ComponentsListEmptyStatesTest : ComposeTest() {

    private val data = ComponentsData(
        activities = listOf(
            ComponentEntry(
                kind = ComponentKind.ACTIVITY,
                packageName = "com.example.app",
                className = "com.example.app.MainActivity",
                isExported = true,
            ),
        ),
    )

    private fun setList(state: ComponentsUiState, query: String) {
        val filtered = (state as? ComponentsUiState.Ready)?.data?.filter(query) ?: ComponentsData()
        composeTestRule.setContent {
            PreviewWrapper {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    appComponentsItems(
                        state = state,
                        filtered = filtered,
                        query = query,
                        onComponentClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun `a package without components says so`() {
        setList(ComponentsUiState.Ready(ComponentsData()), query = "")

        composeTestRule.onNodeWithText("No components found").assertExists()
    }

    @Test
    fun `a query with no matches names the query`() {
        setList(ComponentsUiState.Ready(data), query = "zzz")

        composeTestRule.onNodeWithText("No components match “zzz”").assertExists()
        composeTestRule.onNodeWithText("No components found").assertDoesNotExist()
    }

    @Test
    fun `an empty package keeps its own message even with a query`() {
        setList(ComponentsUiState.Ready(ComponentsData()), query = "zzz")

        composeTestRule.onNodeWithText("No components found").assertExists()
        composeTestRule.onNodeWithText("No components match “zzz”").assertDoesNotExist()
    }

    @Test
    fun `a matching query renders the group`() {
        setList(ComponentsUiState.Ready(data), query = "main")

        composeTestRule.onNodeWithText("ACTIVITIES").assertExists()
        composeTestRule.onNodeWithText("MainActivity").assertExists()
    }
}
