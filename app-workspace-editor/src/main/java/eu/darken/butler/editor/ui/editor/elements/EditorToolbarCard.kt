package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.KeyboardArrowUp
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.editor.R
import eu.darken.butler.editor.ui.editor.EditorPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.common.CutoutAwareColumn
import eu.darken.butler.workspace.ui.common.CutoutCard
import eu.darken.butler.workspace.ui.common.CutoutCardDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun EditorToolbarCard(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    title: CaString,
    subTitle: CaString,
    isModified: Boolean,
    progress: Progress.Data?,
    hasContent: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onAction: (EditorPageAction) -> Unit,
    collapsedFraction: Float = 0f,
) {
    val isLoading = progress != null
    val isCollapsed = collapsedFraction > 0.5f
    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) 8.dp else 16.dp,
        label = "cardPadding"
    )

    val minHeight by animateDpAsState(
        targetValue = if (isCollapsed) WorkspaceButtonDefaults.sizeCompact else WorkspaceButtonDefaults.sizeDefault,
        label = "minHeight",
    )

    CutoutCard(
        modifier = modifier
            .fillMaxWidth()
            .requiredHeightIn(min = minHeight),
        cutoutContent = if (design.isSingle) {
            {
                WorkspaceButton(
                    currentWorkspaceId = workspaceId,
                    buttonSize = if (isCollapsed) WorkspaceButtonDefaults.sizeCompact else WorkspaceButtonDefaults.sizeDefault,
                )
            }
        } else null,
        cutoutFullHeight = isCollapsed,
        gapDistance = if (isCollapsed) CutoutCardDefaults.GapDistanceCollapsed else CutoutCardDefaults.GapDistanceExpanded,
        contentPadding = CutoutCardDefaults.contentPadding(
            start = cardPadding,
            top = cardPadding,
            end = cardPadding,
            bottom = if (isCollapsed) cardPadding else 8.dp,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (isCollapsed) {
            // Collapsed state - compact single row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = Icons.TwoTone.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = title.asComposable(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Show loading indicator or modified indicator
                if (progress != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = progress.primary.asComposable(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        val displayValue = progress.count.displayValue.asComposable()
                        if (displayValue.isNotEmpty()) {
                            Text(
                                text = displayValue,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                } else if (isModified) {
                    Icon(
                        modifier = Modifier.size(14.dp),
                        imageVector = Icons.TwoTone.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        } else {
            // Expanded state - full interactive card
            CutoutAwareColumn(
                cutoutWidth = cutoutWidth,
                cutoutHeight = cutoutHeight,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    ) {
                        Text(
                            text = title.asComposable(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Text(
                            text = subTitle.asComposable(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    // Show modified indicator (loading is shown in actions section below)
                    if (isModified && !isLoading) {
                        Icon(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(16.dp),
                            imageVector = Icons.TwoTone.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }

                // Actions section below (hidden during loading)
                if (progress != null) {
                    // Loading progress row - replaces action buttons
                    // Layout: [🔄] Primary • Secondary    DisplayValue    [Cancel]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = progress.primary.asComposable(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        val secondary = progress.secondary.asComposable()
                        if (secondary.isNotEmpty()) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                            Text(
                                text = secondary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false),
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        val displayValue = progress.count.displayValue.asComposable()
                        if (displayValue.isNotEmpty()) {
                            Text(
                                text = displayValue,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = { onAction(EditorPageAction.File.CancelOpen) }) {
                            Text(stringResource(R.string.editor_action_cancel_loading))
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                    ) {
                        IconButton(onClick = { onAction(EditorPageAction.File.LaunchPicker) }) {
                            Icon(
                                Icons.TwoTone.Description,
                                contentDescription = stringResource(R.string.editor_action_open)
                            )
                        }

                        IconButton(
                            onClick = { onAction(EditorPageAction.File.Save) },
                            enabled = isModified
                        ) {
                            Icon(Icons.TwoTone.Save, contentDescription = stringResource(R.string.editor_action_save))
                        }

                        if (hasContent) {
                            IconButton(onClick = { onAction(EditorPageAction.File.Close) }) {
                                Icon(
                                    Icons.TwoTone.Close,
                                    contentDescription = stringResource(R.string.editor_action_close)
                                )
                            }
                        }

                        if (canUndo) {
                            IconButton(onClick = { onAction(EditorPageAction.Edit.Undo) }) {
                                Icon(
                                    Icons.TwoTone.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.editor_action_undo)
                                )
                            }
                        }

                        if (canRedo) {
                            IconButton(onClick = { onAction(EditorPageAction.Edit.Redo) }) {
                                Icon(
                                    Icons.TwoTone.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.editor_action_redo)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun EditorToolbarCardPreview() {
    PreviewWrapper {
        EditorToolbarCard(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            title = "example.txt".toCaString(),
            subTitle = "/storage/emulated/0/Documents".toCaString(),
            isModified = true,
            progress = null,
            hasContent = true,
            canUndo = true,
            canRedo = false,
            onAction = {},
        )
    }
}

@Preview2
@Composable
private fun EditorToolbarCardCollapsedPreview() {
    PreviewWrapper {
        EditorToolbarCard(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            title = "example.txt".toCaString(),
            subTitle = "/storage/emulated/0/Documents".toCaString(),
            isModified = true,
            progress = null,
            hasContent = true,
            canUndo = true,
            canRedo = false,
            onAction = {},
            collapsedFraction = 1f,
        )
    }
}

@Preview2
@Composable
private fun EditorToolbarCardLoadingPreview() {
    PreviewWrapper {
        EditorToolbarCard(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            title = "example.txt".toCaString(),
            subTitle = "/storage/emulated/0/Documents".toCaString(),
            isModified = false,
            progress = Progress.Data(
                primary = R.string.editor_progress_opening.toCaString(),
                secondary = "Processing chunk 5 of 20".toCaString(),
                count = Progress.Count.Counter(5, 20),
            ),
            hasContent = false,
            canUndo = false,
            canRedo = false,
            onAction = {},
        )
    }
}

@Preview2
@Composable
private fun EditorToolbarCardLoadingCollapsedPreview() {
    PreviewWrapper {
        EditorToolbarCard(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            title = "example.txt".toCaString(),
            subTitle = "/storage/emulated/0/Documents".toCaString(),
            isModified = false,
            progress = Progress.Data(
                primary = R.string.editor_progress_opening.toCaString(),
                secondary = "Processing chunk 5 of 20".toCaString(),
                count = Progress.Count.Counter(5, 20),
            ),
            hasContent = false,
            canUndo = false,
            canRedo = false,
            onAction = {},
            collapsedFraction = 1f,
        )
    }
}

@Preview2
@Composable
private fun EditorToolbarCardMultiPanePreview() {
    PreviewWrapper {
        EditorToolbarCard(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
            title = "example.txt".toCaString(),
            subTitle = "/storage/emulated/0/Documents".toCaString(),
            isModified = true,
            progress = null,
            hasContent = true,
            canUndo = true,
            canRedo = false,
            onAction = {},
        )
    }
}
