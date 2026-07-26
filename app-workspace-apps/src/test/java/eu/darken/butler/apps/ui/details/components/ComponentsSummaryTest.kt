package eu.darken.butler.apps.ui.details.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.core.details.components.ComponentsUiState
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ComponentsSummaryTest : ComposeTest() {

    private val sampleData = ComponentsData(
        activities = listOf(
            ComponentEntry(
                kind = ComponentKind.ACTIVITY,
                packageName = "com.example",
                className = "com.example.MainActivity",
                isExported = true,
            ),
            ComponentEntry(
                kind = ComponentKind.ACTIVITY,
                packageName = "com.example",
                className = "com.example.SettingsActivity",
                isExported = false,
            ),
        ),
        services = listOf(
            ComponentEntry(
                kind = ComponentKind.SERVICE,
                packageName = "com.example",
                className = "com.example.SyncService",
                isExported = false,
            ),
        ),
    )

    @Test
    fun `ready state shows counts and view-all, click invokes callback`() {
        var viewAll = 0
        composeTestRule.setContent {
            PreviewWrapper {
                ComponentsSummary(
                    state = ComponentsUiState.Ready(sampleData),
                    onViewAll = { viewAll++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Activities").assertIsDisplayed()
        composeTestRule.onNodeWithText("View all").assertIsDisplayed()
        composeTestRule.onNodeWithText("View all").performClick()

        viewAll shouldBe 1
    }

    @Test
    fun `error state shows error message and no view-all`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ComponentsSummary(
                    state = ComponentsUiState.Error,
                    onViewAll = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Could not load components").assertIsDisplayed()
        composeTestRule.onNodeWithText("View all").assertDoesNotExist()
    }

    @Test
    fun `empty ready state shows empty message and no view-all`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ComponentsSummary(
                    state = ComponentsUiState.Ready(ComponentsData()),
                    onViewAll = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No components found").assertIsDisplayed()
        composeTestRule.onNodeWithText("View all").assertDoesNotExist()
    }
}
