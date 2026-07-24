package eu.darken.butler.apps.ui.details

import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ComponentsSummaryTest : ComposeTest() {

    private val sampleData = ComponentsData(
        activities = listOf(
            ActivityInfo().apply { name = "com.example.MainActivity"; exported = true },
            ActivityInfo().apply { name = "com.example.SettingsActivity"; exported = false },
        ),
        services = listOf(ServiceInfo().apply { name = "com.example.SyncService" }),
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
