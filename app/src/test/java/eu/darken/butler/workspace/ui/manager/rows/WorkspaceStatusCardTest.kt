package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerFilter
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class WorkspaceStatusCardTest : ComposeTest() {

    @Test
    fun `displays workspace count`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 0,
                    attentionCount = 0,
                )
            }
        }

        composeTestRule.onNodeWithText("5", substring = true).assertIsDisplayed()
    }

    @Test
    fun `displays operations count`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 1,
                    operationsCount = 3,
                    attentionCount = 0,
                )
            }
        }

        composeTestRule.onNodeWithText("3", substring = true).assertIsDisplayed()
    }

    @Test
    fun `displays attention count`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 1,
                    operationsCount = 0,
                    attentionCount = 2,
                )
            }
        }

        composeTestRule.onNodeWithText("2", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tabs click callback is invoked`() {
        var clicked = false

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 0,
                    attentionCount = 0,
                    onTabsClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Select all tabs").performClick()

        clicked shouldBe true
    }

    @Test
    fun `the tabs chip reports the selection while selecting`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 0,
                    attentionCount = 0,
                    selectedCount = 2,
                )
            }
        }

        composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Select all tabs").assertCountEquals(0)
    }

    /**
     * Clearing hangs off the trailing X alone; the chip body carries no click action of its own.
     * Wiring the body to it - as WorkspaceInfoBar's InfoChip does - would wipe a hand-picked
     * selection on the tap that was only meant to read the count.
     */
    @Test
    fun `only the selection chip's remove affordance clears`() {
        var cleared = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 0,
                    attentionCount = 0,
                    selectedCount = 2,
                    onClearSelection = { cleared++ },
                )
            }
        }

        val chip = composeTestRule.onNodeWithContentDescription("Clear selection")
        chip.assertHasNoClickAction()

        chip.onChildren().filterToOne(hasClickAction()).performClick()

        cleared shouldBe 1
    }

    /**
     * A filter toggled mid-selection would hide checked cards while the count and the batch actions
     * keep including them.
     */
    @Test
    fun `filter chips are inert while selecting`() {
        val clicks = mutableListOf<WorkspaceManagerFilter>()

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 3,
                    attentionCount = 2,
                    pausedCount = 1,
                    unsavedCount = 1,
                    selectedCount = 2,
                    onFilterClick = { clicks.add(it) },
                )
            }
        }

        FILTER_DESCRIPTIONS.values.forEach {
            composeTestRule.onNodeWithContentDescription(it).performClick()
        }

        clicks shouldBe emptyList()
    }

    @Test
    fun `each filter chip reports its own facet`() {
        val clicks = mutableListOf<WorkspaceManagerFilter>()

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 4,
                    operationsCount = 3,
                    attentionCount = 2,
                    pausedCount = 1,
                    unsavedCount = 1,
                    onFilterClick = { clicks.add(it) },
                )
            }
        }

        WorkspaceManagerFilter.entries.forEach { filter ->
            composeTestRule.onNodeWithContentDescription(FILTER_DESCRIPTIONS.getValue(filter)).performClick()
        }

        clicks shouldBe WorkspaceManagerFilter.entries
    }

    /** Four facets would otherwise open the manager on a wall of zeroes. */
    @Test
    fun `a facet with nothing to show is left out`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 3,
                    attentionCount = 0,
                    pausedCount = 0,
                    unsavedCount = 0,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(
            FILTER_DESCRIPTIONS.getValue(WorkspaceManagerFilter.OPERATIONS)
        ).assertIsDisplayed()
        listOf(
            WorkspaceManagerFilter.ATTENTION,
            WorkspaceManagerFilter.PAUSED,
            WorkspaceManagerFilter.UNSAVED,
        ).forEach {
            composeTestRule
                .onAllNodesWithContentDescription(FILTER_DESCRIPTIONS.getValue(it))
                .assertCountEquals(0)
        }
    }

    /**
     * The last operation finishing while its filter is on must not take the chip with it: the grid
     * is empty at that moment and that chip is the only way back.
     */
    @Test
    fun `the active facet stays after its count drops to zero`() {
        var clicked: WorkspaceManagerFilter? = null
        var operations by mutableStateOf(3)

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = operations,
                    attentionCount = 0,
                    activeFilter = WorkspaceManagerFilter.OPERATIONS,
                    onFilterClick = { clicked = it },
                )
            }
        }

        operations = 0
        composeTestRule.waitForIdle()

        val chip = composeTestRule.onNodeWithContentDescription(
            FILTER_DESCRIPTIONS.getValue(WorkspaceManagerFilter.OPERATIONS)
        )
        chip.assertIsDisplayed()
        chip.performClick()

        clicked shouldBe WorkspaceManagerFilter.OPERATIONS
    }

    /**
     * A facet dropping out shifts every chip after it. Without a key per facet those would inherit
     * each other's slot, so the tap that follows would report the wrong one.
     */
    @Test
    fun `a chip vanishing mid-row leaves the later ones reporting themselves`() {
        var clicked: WorkspaceManagerFilter? = null
        var attention by mutableStateOf(2)

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceStatusCard(
                    workspaceCount = 5,
                    operationsCount = 3,
                    attentionCount = attention,
                    pausedCount = 1,
                    unsavedCount = 1,
                    onFilterClick = { clicked = it },
                )
            }
        }

        attention = 0
        composeTestRule.waitForIdle()

        composeTestRule
            .onAllNodesWithContentDescription(FILTER_DESCRIPTIONS.getValue(WorkspaceManagerFilter.ATTENTION))
            .assertCountEquals(0)
        composeTestRule
            .onNodeWithContentDescription(FILTER_DESCRIPTIONS.getValue(WorkspaceManagerFilter.PAUSED))
            .performClick()

        clicked shouldBe WorkspaceManagerFilter.PAUSED
    }

    companion object {
        private val FILTER_DESCRIPTIONS = mapOf(
            WorkspaceManagerFilter.OPERATIONS to "Filter by tabs with operations",
            WorkspaceManagerFilter.ATTENTION to "Filter by tabs needing attention",
            WorkspaceManagerFilter.PAUSED to "Filter by paused tabs",
            WorkspaceManagerFilter.UNSAVED to "Filter by tabs with unsaved changes",
        )
    }
}
