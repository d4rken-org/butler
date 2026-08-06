package eu.darken.butler.apps.ui.details.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
        composeTestRule.onNodeWithText("View all components").assertIsDisplayed()
        composeTestRule.onNodeWithText("View all components").performClick()

        viewAll shouldBe 1
    }

    /**
     * The per-type rows and the action row have differently sized labels, so the counts may only
     * line up if both rows reserve the same trailing geometry (count, gap, 24dp chevron slot).
     *
     * Both sides are queried on the UNMERGED tree: the action row is `clickable`, which merges its
     * descendants, so in the merged tree "3" resolves to the whole row and its bounds would be
     * compared against a text's.
     */
    @Test
    fun `the total lines up with the per-type counts`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ComponentsSummary(
                    state = ComponentsUiState.Ready(sampleData),
                    onViewAll = {},
                )
            }
        }

        val activitiesCount = composeTestRule
            .onNodeWithText("2", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().left
        val total = composeTestRule
            .onNodeWithText("3", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().left

        total shouldBe activitiesCount
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
        composeTestRule.onNodeWithText("View all components").assertDoesNotExist()
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
        composeTestRule.onNodeWithText("View all components").assertDoesNotExist()
    }
}
