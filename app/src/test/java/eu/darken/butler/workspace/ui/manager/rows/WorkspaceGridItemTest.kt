package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope
import testhelpers.ComposeTest

class WorkspaceGridItemTest : ComposeTest() {

    private val noopReorderableScope = object : ReorderableCollectionItemScope {
        override fun Modifier.draggableHandle(
            enabled: Boolean,
            interactionSource: MutableInteractionSource?,
            onDragStarted: (Offset) -> Unit,
            onDragStopped: () -> Unit,
            dragGestureDetector: DragGestureDetector,
        ): Modifier = this

        override fun Modifier.longPressDraggableHandle(
            enabled: Boolean,
            interactionSource: MutableInteractionSource?,
            onDragStarted: (Offset) -> Unit,
            onDragStopped: () -> Unit,
        ): Modifier = this
    }

    private fun item(
        isSubWorkspace: Boolean = false,
        isPaused: Boolean = false,
        canPause: Boolean = false,
        isRecovery: Boolean = false,
        stackDepth: Int = 0,
    ): WorkspaceManagerViewModel.WorkspaceItem {
        val id = Workspace.Id()
        return WorkspaceManagerViewModel.WorkspaceItem(
            id = id,
            topId = id,
            type = Workspace.Type.EXPLORER,
            title = "/sdcard/Download".toCaString(),
            autoTitle = "/sdcard/Download".toCaString(),
            subtitle = null,
            isSubWorkspace = isSubWorkspace,
            isRecovery = isRecovery,
            isPaused = isPaused,
            canPause = canPause,
            stackDepth = stackDepth,
        )
    }

    @Test
    fun `the overflow menu triggers rename without selecting or closing`() {
        var renamed = 0
        var selected = 0
        var closed = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(),
                    onClose = { closed++ },
                    onSelect = { selected++ },
                    onRename = { renamed++ },
                    livePreview = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()

        renamed shouldBe 1
        selected shouldBe 0
        closed shouldBe 0
    }

    @Test
    fun `a live sub-workspace card has no overflow menu`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(isSubWorkspace = true),
                    onClose = {},
                    onSelect = {},
                    onRename = {},
                    livePreview = false,
                )
            }
        }

        composeTestRule.onAllNodesWithContentDescription("More options").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("Close tab").assertExists()
    }

    /**
     * A tab is paused together with its opted-in overlays, so a child card CAN be paused. It has to
     * offer its way back - and only that: rename is lost on a workspace that is never persisted, and
     * a child is only ever paused as part of its owner, never on its own.
     */
    @Test
    fun `a paused sub-workspace card offers resume and nothing else`() {
        var resumed = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(isSubWorkspace = true, isPaused = true, canPause = true),
                    onClose = {},
                    onSelect = {},
                    onRename = {},
                    onResume = { resumed++ },
                    livePreview = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("More options").performClick()

        composeTestRule.onAllNodesWithText("Rename").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Pause").assertCountEquals(0)
        composeTestRule.onNodeWithText("Resume").performClick()

        resumed shouldBe 1
    }

    /**
     * The header affordances must not vary with pause state - that is what moving pause/resume into
     * the overflow menu means. Comparing content descriptions catches a reintroduced icon button,
     * which a text-only assertion would miss since the old icons were labelled by description.
     */
    @Test
    fun `pause state adds no affordance to the card header`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Column {
                    WorkspaceGridItem(
                        reorderableScope = noopReorderableScope,
                        workspace = item(),
                        onClose = {},
                        onSelect = {},
                        livePreview = false,
                    )
                    WorkspaceGridItem(
                        reorderableScope = noopReorderableScope,
                        workspace = item(canPause = true),
                        onClose = {},
                        onSelect = {},
                        livePreview = false,
                    )
                    WorkspaceGridItem(
                        reorderableScope = noopReorderableScope,
                        workspace = item(isPaused = true),
                        onClose = {},
                        onSelect = {},
                        livePreview = false,
                    )
                }
            }
        }

        val headers = composeTestRule
            .onAllNodesWithTag(TEST_TAG_WORKSPACE_CARD_HEADER, useUnmergedTree = true)
            .fetchSemanticsNodes()

        headers shouldHaveSize 3
        headers.map { it.contentDescriptions() }.distinct() shouldHaveSize 1
    }

    @Test
    fun `the overflow menu triggers pause without selecting the card`() {
        var paused = 0
        var selected = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(canPause = true),
                    onClose = {},
                    onSelect = { selected++ },
                    onPause = { paused++ },
                    livePreview = false,
                )
            }
        }

        composeTestRule.onAllNodesWithText("Pause").assertCountEquals(0)

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Pause").performClick()

        paused shouldBe 1
        selected shouldBe 0
    }

    @Test
    fun `the overflow menu triggers resume without selecting the card`() {
        var resumed = 0
        var selected = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(isPaused = true),
                    onClose = {},
                    onSelect = { selected++ },
                    onResume = { resumed++ },
                    livePreview = false,
                )
            }
        }

        composeTestRule.onAllNodesWithText("Resume").assertCountEquals(0)

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Resume").performClick()

        resumed shouldBe 1
        selected shouldBe 0
    }

    @Test
    fun `a workspace that cannot pause only offers rename`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(),
                    onClose = {},
                    onSelect = {},
                    livePreview = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("More options").performClick()

        composeTestRule.onNodeWithText("Rename").assertExists()
        composeTestRule.onAllNodesWithText("Pause").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Resume").assertCountEquals(0)
    }

    @Test
    fun `a paused workspace offers resume instead of pause`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(isPaused = true, canPause = true),
                    onClose = {},
                    onSelect = {},
                    livePreview = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("More options").performClick()

        composeTestRule.onNodeWithText("Resume").assertExists()
        composeTestRule.onAllNodesWithText("Pause").assertCountEquals(0)
    }

    @Test
    fun `the stack badge only shows when something is stacked on the tab`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(stackDepth = 2),
                    onClose = {},
                    onSelect = {},
                    livePreview = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Stacked on this tab").assertExists()
    }

    @Test
    fun `a plain tab card has no stack badge`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(),
                    onClose = {},
                    onSelect = {},
                    livePreview = false,
                )
            }
        }

        composeTestRule.onAllNodesWithContentDescription("Stacked on this tab").assertCountEquals(0)
    }

    @Test
    fun `tapping a card selects it`() {
        var selected = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(),
                    onClose = {},
                    onSelect = { selected++ },
                    livePreview = false,
                )
            }
        }

        composeTestRule.onNodeWithText("/sdcard/Download").performClick()

        selected shouldBe 1
    }

    /** A recovery card stands in for a workspace no pane renders, so there is nothing to select. */
    @Test
    fun `a recovery card cannot be selected`() {
        var selected = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(isSubWorkspace = true, isRecovery = true),
                    onClose = {},
                    onSelect = { selected++ },
                    livePreview = false,
                )
            }
        }

        composeTestRule.onNodeWithText("/sdcard/Download").performClick()

        selected shouldBe 0
    }

    private fun SemanticsNode.contentDescriptions(): List<String> =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            children.flatMap { it.contentDescriptions() }

    @Test
    fun `selection mode swaps the per-card actions for a checkbox`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(canPause = true),
                    onClose = {},
                    onSelect = {},
                    livePreview = false,
                    isSelectionActive = true,
                    isChecked = false,
                )
            }
        }

        composeTestRule.onAllNodesWithContentDescription("Close tab").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("More options").assertCountEquals(0)
        composeTestRule.onNode(isToggleable()).assertExists()
    }

    @Test
    fun `the checkbox toggles the selection instead of opening the tab`() {
        var toggled = 0
        var selected = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(),
                    onClose = {},
                    onSelect = { selected++ },
                    onToggleSelection = { toggled++ },
                    livePreview = false,
                    isSelectionActive = true,
                    isChecked = false,
                )
            }
        }

        composeTestRule.onNode(isToggleable()).performClick()

        toggled shouldBe 1
        selected shouldBe 0
    }

    /**
     * A recovery card cannot be opened, so it never reacts to a plain tap - but it is exactly the
     * kind of card a bulk close is for, so it still takes part in a selection.
     */
    @Test
    fun `a recovery card can be checked even though it cannot be opened`() {
        var toggled = 0
        var selected = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(isRecovery = true),
                    onClose = {},
                    onSelect = { selected++ },
                    onToggleSelection = { toggled++ },
                    livePreview = false,
                    isSelectionActive = true,
                    isChecked = false,
                )
            }
        }

        composeTestRule.onNode(isToggleable()).performClick()

        toggled shouldBe 1
        selected shouldBe 0
    }
}
