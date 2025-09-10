package eu.darken.butler.workspace.ui.manager.rows

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
import androidx.compose.material.icons.twotone.DragIndicator
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

@Composable
fun WorkspaceListItem(
    modifier: Modifier = Modifier,
    reorderableScope: sh.calvin.reorderable.ReorderableCollectionItemScope,
    workspace: WorkspaceManagerViewModel.WorkspaceItem,
    onClose: () -> Unit,
    onSelect: () -> Unit,
    isDragging: Boolean = false,
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = with(reorderableScope) {
                    Modifier
                        .size(40.dp)
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
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.TwoTone.DragIndicator,
                    contentDescription = stringResource(R.string.workspace_row_reorder_content_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Icon(
                imageVector = workspace.type.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = workspace.title.asComposable(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = workspace.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Close,
                    contentDescription = stringResource(R.string.workspace_row_close_content_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspaceListItemPreview() {
    PreviewWrapper {
        WorkspaceListItem(
            modifier = Modifier.padding(16.dp),
            reorderableScope = object : sh.calvin.reorderable.ReorderableCollectionItemScope {
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
            },
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Explorer".toCaString(),
                subtitle = "File explorer"
            ),
            onClose = {},
            onSelect = {},
            isDragging = false
        )
    }
}

@Preview2
@Composable
private fun WorkspaceListItemDraggingPreview() {
    PreviewWrapper {
        WorkspaceListItem(
            modifier = Modifier.padding(16.dp),
            reorderableScope = object : sh.calvin.reorderable.ReorderableCollectionItemScope {
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
            },
            workspace = WorkspaceManagerViewModel.WorkspaceItem(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search".toCaString(),
                subtitle = "File search"
            ),
            onClose = {},
            onSelect = {},
            isDragging = true
        )
    }
}