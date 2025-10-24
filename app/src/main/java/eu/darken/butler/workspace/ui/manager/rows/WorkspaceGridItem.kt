package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import eu.darken.butler.workspace.ui.manager.rows.preview.WorkspacePreview

@Composable
fun WorkspaceGridItem(
    modifier: Modifier = Modifier,
    reorderableScope: sh.calvin.reorderable.ReorderableCollectionItemScope,
    workspace: WorkspaceManagerViewModel.WorkspaceItem,
    onClose: () -> Unit,
    onSelect: () -> Unit,
    livePreview: Boolean = true,
    isDragging: Boolean = false,
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 16.dp else 2.dp,
            pressedElevation = 8.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = with(reorderableScope) {
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp)
                        .draggableHandle(
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

                Text(
                    modifier = Modifier.weight(1f),
                    text = workspace.title.asComposable(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

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

            WorkspacePreview(
                modifier = Modifier.fillMaxWidth(),
                workspaceId = workspace.id,
                type = workspace.type,
                livePreview = livePreview,
            )
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
@Composable
private fun WorkspaceGridItemPreview() {
    PreviewWrapper {
        WorkspaceGridItem(
            modifier = Modifier.padding(16.dp),
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "/storage/emulated/0/Download/MyFile/Somepath/that/is/very/long/tooLong".toCaString(),
                subtitle = "File explorer for browsing and managing files".toCaString(),
            ),
            onClose = {},
            onSelect = {},
            isDragging = false
        )
    }
}

@Preview2
@Composable
private fun WorkspaceGridItemSearcherPreview() {
    PreviewWrapper {
        WorkspaceGridItem(
            modifier = Modifier.padding(16.dp),
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search".toCaString(),
                subtitle = "Search for files and folders".toCaString(),
            ),
            onClose = {},
            onSelect = {},
            isDragging = false
        )
    }
}

@Preview2
@Composable
private fun WorkspaceGridItemEditorPreview() {
    PreviewWrapper {
        WorkspaceGridItem(
            modifier = Modifier.padding(16.dp),
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = Workspace.Id(),
                type = Workspace.Type.EDITOR,
                title = "Editor".toCaString(),
                subtitle = "Text editor".toCaString(),
            ),
            onClose = {},
            onSelect = {},
            isDragging = false
        )
    }
}

@Preview2
@Composable
private fun WorkspaceGridItemDraggingPreview() {
    PreviewWrapper {
        WorkspaceGridItem(
            modifier = Modifier.padding(16.dp),
            reorderableScope = createMockReorderableScope(),
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search".toCaString(),
                subtitle = "Search for files and folders".toCaString(),
            ),
            onClose = {},
            onSelect = {},
            livePreview = false,
            isDragging = true
        )
    }
}