package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material.icons.twotone.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import eu.darken.butler.workspace.ui.manager.rows.preview.WorkspacePreview
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.workspace.R as WorkspaceR

const val TEST_TAG_WORKSPACE_CARD_HEADER = "workspace_card_header"
const val TEST_TAG_WORKSPACE_CARD_UNSAVED = "workspace_card_unsaved"

@Composable
fun WorkspaceGridItem(
    modifier: Modifier = Modifier,
    reorderableScope: sh.calvin.reorderable.ReorderableCollectionItemScope,
    workspace: WorkspaceManagerViewModel.WorkspaceItem,
    onClose: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    livePreview: Boolean = true,
    isDragging: Boolean = false,
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
    isFocused: Boolean = false,
    isSelected: Boolean = false,
    currentPaneCount: Int = 1,
) {
    val haptic = LocalHapticFeedback.current
    val needsAttention = workspace.attentionCount > 0
    val attentionColor = MaterialTheme.colorScheme.error
    var showOverflowMenu by remember { mutableStateOf(false) }

    val glowModifier = if (needsAttention) {
        Modifier.drawBehind {
            val glowSize = 3.dp.toPx()
            drawRoundRect(
                color = attentionColor.copy(alpha = 0.4f),
                cornerRadius = CornerRadius(20.dp.toPx()),
                size = Size(size.width + glowSize * 2, size.height + glowSize * 2),
                topLeft = Offset(-glowSize, -glowSize),
                style = Fill,
            )
        }
    } else {
        Modifier
    }

    Box(modifier = modifier.then(glowModifier)) {
        Card(
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isFocused -> MaterialTheme.colorScheme.primaryContainer
                    isSelected -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (isDragging) 16.dp else 2.dp,
                pressedElevation = 8.dp
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // A recovery card stands in for a workspace no pane renders, so there is nothing
                    // to select - it only offers Close.
                    .then(if (workspace.isRecovery) Modifier else Modifier.clickable { onSelect() }),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = with(reorderableScope) {
                        Modifier
                            .fillMaxWidth()
                            .testTag(TEST_TAG_WORKSPACE_CARD_HEADER)
                            .padding(start = 10.dp, top = 6.dp, end = 6.dp)
                            // Long press, not press: the handle drags along the grid's own scroll
                            // axis, so a press-based detector turns every scroll that starts on a
                            // card header into a reorder.
                            .longPressDraggableHandle(
                                onDragStarted = {
                                    onDragStarted()
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    onDragStopped()
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                },
                            )
                    },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        modifier = Modifier.size(16.dp),
                        imageVector = workspace.type.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    val title = workspace.customTitle?.takeIf { it.isNotBlank() }
                        ?: workspace.type.label.asComposable()
                    Text(
                        modifier = Modifier.weight(1f),
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Deliberately not the error-red attention glow: an edit that has not been
                    // saved yet is a normal working state, not a fault.
                    if (workspace.hasUnsavedChanges) {
                        Icon(
                            modifier = Modifier
                                .size(14.dp)
                                .testTag(TEST_TAG_WORKSPACE_CARD_UNSAVED),
                            imageVector = Icons.TwoTone.Edit,
                            contentDescription = stringResource(R.string.workspace_row_unsaved_content_desc),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    // Sub-workspaces are not persisted, so a rename on them would silently be lost -
                    // but a paused one still needs its Resume entry: a tab is now paused together
                    // with its opted-in modal children, so a child card CAN be paused. It never
                    // offers Pause itself, because children only ever go down with their owner.
                    val canRename = !workspace.isSubWorkspace
                    val canPauseFromCard = !workspace.isSubWorkspace && workspace.canPause
                    if (canRename || canPauseFromCard || workspace.isPaused) {
                        Box {
                            IconButton(
                                modifier = Modifier.size(24.dp),
                                onClick = { showOverflowMenu = true },
                            ) {
                                Icon(
                                    modifier = Modifier.size(18.dp),
                                    imageVector = Icons.TwoTone.MoreVert,
                                    contentDescription = stringResource(R.string.workspace_row_more_options_content_desc),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }

                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                when {
                                    workspace.isPaused -> DropdownMenuItem(
                                        text = { Text(stringResource(R.string.workspace_row_resume_action)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.TwoTone.PlayCircle,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onResume()
                                        },
                                    )
                                    canPauseFromCard -> DropdownMenuItem(
                                        text = { Text(stringResource(R.string.workspace_row_pause_action)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.TwoTone.PauseCircle,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onPause()
                                        },
                                    )
                                }

                                if (canRename) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(CommonR.string.general_rename_action)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.TwoTone.Edit,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onRename()
                                        },
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        modifier = Modifier.size(24.dp),
                        onClick = onClose,
                    ) {
                        Icon(
                            modifier = Modifier.size(18.dp),
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = stringResource(R.string.workspace_row_close_content_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    WorkspacePreview(
                        modifier = Modifier.fillMaxWidth(),
                        // The card collapses a whole stack, so it previews what is on top of the tab
                        workspaceId = workspace.topId,
                        type = workspace.type,
                        livePreview = livePreview,
                        paneNumber = workspace.paneNumber,
                        shouldShowBadge = workspace.paneNumber != null && currentPaneCount > 1,
                        stackDepth = workspace.stackDepth,
                        contentAlpha = if (workspace.isPaused) 0.4f else 1f,
                    ) {
                        WorkspacePreviewInfoBar(
                            modifier = Modifier.align(Alignment.BottomStart),
                            primary = workspace.autoTitle,
                            secondary = workspace.subtitle,
                        )
                    }

                    if (workspace.isPaused) {
                        Text(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            text = stringResource(WorkspaceR.string.workspace_paused_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun createMockReorderableScope() = object : sh.calvin.reorderable.ReorderableCollectionItemScope {
    override fun Modifier.draggableHandle(
        enabled: Boolean,
        interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource?,
        onDragStarted: (androidx.compose.ui.geometry.Offset) -> Unit,
        onDragStopped: () -> Unit,
        dragGestureDetector: sh.calvin.reorderable.DragGestureDetector
    ): Modifier = this

    override fun Modifier.longPressDraggableHandle(
        enabled: Boolean,
        interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource?,
        onDragStarted: (androidx.compose.ui.geometry.Offset) -> Unit,
        onDragStopped: () -> Unit
    ): Modifier = this
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemPreview() {
    val id = Workspace.Id()
    WorkspaceGridItem(
        modifier = Modifier.padding(16.dp),
        reorderableScope = createMockReorderableScope(),
        workspace = WorkspaceManagerViewModel.WorkspaceItem(
            id = id,
            topId = id,
            type = Workspace.Type.EXPLORER,
            title = "/storage/emulated/0/Download/MyFile/Somepath/that/is/very/long/tooLong".toCaString(),
            autoTitle = "/storage/emulated/0/Download/MyFile/Somepath/that/is/very/long/tooLong".toCaString(),
            subtitle = "Recover deleted files".toCaString(),
        ),
        onClose = {},
        onSelect = {},
        isDragging = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemInfoBarVariantsPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Both bar lines
        val editorId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = editorId,
                topId = editorId,
                type = Workspace.Type.EDITOR,
                title = "build.gradle.kts".toCaString(),
                autoTitle = "build.gradle.kts".toCaString(),
                subtitle = "/storage/emulated/0/Projects/butler".toCaString(),
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )

        // Primary only
        val explorerId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = explorerId,
                topId = explorerId,
                type = Workspace.Type.EXPLORER,
                title = "Home".toCaString(),
                autoTitle = "Home".toCaString(),
                subtitle = null,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )

        // Neither - no bar at all, card height is unchanged
        val saverId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = saverId,
                topId = saverId,
                type = Workspace.Type.SAVER,
                title = "".toCaString(),
                autoTitle = "".toCaString(),
                subtitle = "   ".toCaString(),
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemCustomNamePreview() {
    val id = Workspace.Id()
    WorkspaceGridItem(
        modifier = Modifier.padding(16.dp),
        reorderableScope = createMockReorderableScope(),
        workspace = WorkspaceManagerViewModel.WorkspaceItem(
            id = id,
            topId = id,
            type = Workspace.Type.EXPLORER,
            title = "Holiday photos".toCaString(),
            autoTitle = "/storage/emulated/0/DCIM/Camera".toCaString(),
            subtitle = null,
            customTitle = "Holiday photos",
        ),
        onClose = {},
        onSelect = {},
        livePreview = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemSearcherPreview() {
    val id = Workspace.Id()
    WorkspaceGridItem(
        modifier = Modifier.padding(16.dp),
        reorderableScope = createMockReorderableScope(),
        workspace = WorkspaceManagerViewModel.WorkspaceItem(
            id = id,
            topId = id,
            type = Workspace.Type.SEARCHER,
            title = "*.log".toCaString(),
            autoTitle = "*.log".toCaString(),
            subtitle = "Device storage, SD card".toCaString(),
        ),
        onClose = {},
        onSelect = {},
        isDragging = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemDraggingPreview() {
    val id = Workspace.Id()
    WorkspaceGridItem(
        modifier = Modifier.padding(16.dp),
        reorderableScope = createMockReorderableScope(),
        workspace = WorkspaceManagerViewModel.WorkspaceItem(
            id = id,
            topId = id,
            type = Workspace.Type.SEARCHER,
            title = "*.log".toCaString(),
            autoTitle = "*.log".toCaString(),
            subtitle = "Device storage".toCaString(),
        ),
        onClose = {},
        onSelect = {},
        livePreview = false,
        isDragging = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemFocusStatesPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Focused workspace in pane 1 - SHOWS BADGE (multi-pane mode)
        val focusedId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = focusedId,
                topId = focusedId,
                type = Workspace.Type.EXPLORER,
                title = "/storage/emulated/0/Download".toCaString(),
                autoTitle = "/storage/emulated/0/Download".toCaString(),
                subtitle = null,
                isFocused = true,
                isSelected = true,
                paneNumber = 0,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
            isFocused = true,
            isSelected = true,
            currentPaneCount = 2,
        )

        // Selected but not focused in pane 2 - SHOWS BADGE
        val selectedId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = selectedId,
                topId = selectedId,
                type = Workspace.Type.SEARCHER,
                title = "report".toCaString(),
                autoTitle = "report".toCaString(),
                subtitle = "SD card".toCaString(),
                isFocused = false,
                isSelected = true,
                paneNumber = 1,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
            isFocused = false,
            isSelected = true,
            currentPaneCount = 2,
        )

        // Normal workspace - NO BADGE (not selected)
        val idleId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = idleId,
                topId = idleId,
                type = Workspace.Type.EDITOR,
                title = "notes.md".toCaString(),
                autoTitle = "notes.md".toCaString(),
                subtitle = "/storage/emulated/0/Documents".toCaString(),
                isFocused = false,
                isSelected = false,
                paneNumber = null,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
            isFocused = false,
            isSelected = false,
            currentPaneCount = 2,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemAttentionPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Workspace needs attention
        val attentionId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = attentionId,
                topId = attentionId,
                type = Workspace.Type.EXPLORER,
                title = "/storage/emulated/0/Download".toCaString(),
                autoTitle = "/storage/emulated/0/Download".toCaString(),
                subtitle = "3 errors occurred".toCaString(),
                attentionCount = 3,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )

        // Normal workspace for comparison
        val calmId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = calmId,
                topId = calmId,
                type = Workspace.Type.EXPLORER,
                title = "Trash".toCaString(),
                autoTitle = "Trash".toCaString(),
                subtitle = "Recover deleted files".toCaString(),
                attentionCount = 0,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemPauseStatesPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Live and pausable - the pause action sits in the overflow menu, so the header looks idle
        val liveId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = liveId,
                topId = liveId,
                type = Workspace.Type.EXPLORER,
                title = "/storage/emulated/0/Download".toCaString(),
                autoTitle = "/storage/emulated/0/Download".toCaString(),
                subtitle = null,
                canPause = true,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )

        // Paused - dimmed thumbnail and chip, but the info bar stays fully legible
        val pausedId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = pausedId,
                topId = pausedId,
                type = Workspace.Type.SEARCHER,
                title = "*.log".toCaString(),
                autoTitle = "*.log".toCaString(),
                subtitle = "Device storage, SD card".toCaString(),
                isPaused = true,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )

        // Busy - neither pausable nor paused, so the overflow menu only offers rename
        val busyId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = busyId,
                topId = busyId,
                type = Workspace.Type.EDITOR,
                title = "notes.md".toCaString(),
                autoTitle = "notes.md".toCaString(),
                subtitle = "/storage/emulated/0/Documents".toCaString(),
                canPause = false,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemStackedPreview() {
    // The tab is an Apps workspace, but what sits on top of it is an app's details page - so that is
    // what the card names and previews, with the badge marking the stack underneath.
    WorkspaceGridItem(
        modifier = Modifier.padding(16.dp),
        reorderableScope = createMockReorderableScope(),
        workspace = WorkspaceManagerViewModel.WorkspaceItem(
            id = Workspace.Id(),
            topId = Workspace.Id(),
            type = Workspace.Type.APP_DETAILS,
            title = "Butler".toCaString(),
            autoTitle = "Butler".toCaString(),
            subtitle = "eu.darken.butler".toCaString(),
            stackDepth = 1,
        ),
        onClose = {},
        onSelect = {},
        livePreview = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemRecoveryPreview() {
    val id = Workspace.Id()
    WorkspaceGridItem(
        modifier = Modifier.padding(16.dp),
        reorderableScope = createMockReorderableScope(),
        workspace = WorkspaceManagerViewModel.WorkspaceItem(
            id = id,
            topId = id,
            type = Workspace.Type.EXPLORER,
            title = "Pick a folder".toCaString(),
            autoTitle = "Pick a folder".toCaString(),
            subtitle = null,
            isSubWorkspace = true,
            isRecovery = true,
        ),
        onClose = {},
        onSelect = {},
        livePreview = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceGridItemUnsavedPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Unsaved edits alone: the tertiary marker, no error glow
        val dirtyId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = dirtyId,
                topId = dirtyId,
                type = Workspace.Type.EDITOR,
                title = "notes.txt".toCaString(),
                autoTitle = "notes.txt".toCaString(),
                subtitle = "/storage/emulated/0/Documents".toCaString(),
                hasUnsavedChanges = true,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )

        // Both at once: the two signals have to stay tellable apart
        val bothId = Workspace.Id()
        WorkspaceGridItem(
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = bothId,
                topId = bothId,
                type = Workspace.Type.EDITOR,
                title = "config.xml".toCaString(),
                autoTitle = "config.xml".toCaString(),
                subtitle = "Save failed".toCaString(),
                attentionCount = 1,
                hasUnsavedChanges = true,
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
        )
    }
}
