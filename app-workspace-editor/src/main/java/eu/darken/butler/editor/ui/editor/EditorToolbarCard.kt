package eu.darken.butler.editor.ui.editor

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.FormatListNumbered
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.KeyboardArrowUp
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun EditorToolbarCard(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    fileName: String,
    isModified: Boolean,
    hasFile: Boolean,
    isLoading: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceActionHandler: WorkspaceActionHandler?,
    onAction: (EditorPageAction) -> Unit,
    modifier: Modifier = Modifier,
    collapsedFraction: Float = 0f,
) {
    val isCollapsed = collapsedFraction > 0.5f
    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) 8.dp else 16.dp,
        label = "cardPadding"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        text = fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Show loading indicator or modified indicator
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp)
                        )
                    } else if (isModified) {
                        Text(
                            text = stringResource(R.string.editor_modified_indicator),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (design.isSingle) {
                        WorkspaceButton(
                            buttonSize = 40.dp,
                            state = workspaceButtonState,
                            workspaceActionHandler = workspaceActionHandler,
                        )
                    }
                }
            } else {
                // Expanded state - full interactive card
                // Title section on top
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )

                    // Show loading indicator or modified indicator in same position
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(horizontal = 8.dp)
                        )
                    } else if (isModified) {
                        Text(
                            text = stringResource(R.string.editor_modified_indicator),
                            modifier = Modifier.padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    if (design.isSingle) {
                        Spacer(modifier = Modifier.width(8.dp))

                        WorkspaceButton(
                            state = workspaceButtonState,
                            currentWorkspaceId = workspaceId,
                            workspaceActionHandler = workspaceActionHandler,
                        )
                    }
                }

                // Actions section below
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = { onAction(EditorPageAction.File.LaunchPicker) }) {
                        Icon(
                            Icons.TwoTone.Description,
                            contentDescription = stringResource(R.string.editor_action_open)
                        )
                    }

                    // Show save/edit actions when there's content or a file
                    if (hasFile) {
                        IconButton(
                            onClick = { onAction(EditorPageAction.File.Save) },
                            enabled = isModified
                        ) {
                            Icon(Icons.TwoTone.Save, contentDescription = stringResource(R.string.editor_action_save))
                        }

                        IconButton(onClick = { onAction(EditorPageAction.File.Close) }) {
                            Icon(Icons.TwoTone.Close, contentDescription = stringResource(R.string.editor_action_close))
                        }

                        IconButton(
                            onClick = { onAction(EditorPageAction.Edit.Undo) },
                            enabled = canUndo
                        ) {
                            Icon(
                                Icons.TwoTone.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.editor_action_undo)
                            )
                        }

                        IconButton(
                            onClick = { onAction(EditorPageAction.Edit.Redo) },
                            enabled = canRedo
                        ) {
                            Icon(
                                Icons.TwoTone.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.editor_action_redo)
                            )
                        }

                        IconButton(onClick = { onAction(EditorPageAction.Navigation.Search("")) }) {
                            Icon(
                                Icons.TwoTone.Search,
                                contentDescription = stringResource(R.string.editor_action_search)
                            )
                        }

                        IconButton(onClick = { onAction(EditorPageAction.Navigation.GoToLine(0)) }) {
                            Icon(
                                Icons.TwoTone.FormatListNumbered,
                                contentDescription = stringResource(R.string.editor_action_go_to_line)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
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
            fileName = "example.txt",
            isModified = true,
            hasFile = true,
            isLoading = false,
            canUndo = true,
            canRedo = false,
            workspaceButtonState = null,
            workspaceActionHandler = null,
            onAction = {},
            modifier = Modifier.padding(16.dp)
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
            fileName = "loading.txt",
            isModified = false,
            hasFile = true,
            isLoading = true,
            canUndo = false,
            canRedo = false,
            workspaceButtonState = null,
            workspaceActionHandler = null,
            onAction = {},
            modifier = Modifier.padding(16.dp)
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
            fileName = "example.txt",
            isModified = true,
            hasFile = true,
            isLoading = false,
            canUndo = true,
            canRedo = false,
            workspaceButtonState = null,
            workspaceActionHandler = null,
            onAction = {},
            modifier = Modifier.padding(16.dp),
            collapsedFraction = 1f
        )
    }
}

@Preview2
@Composable
private fun EditorToolbarCardNoFilePreview() {
    PreviewWrapper {
        EditorToolbarCard(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            fileName = "Untitled",
            isModified = false,
            hasFile = false,
            isLoading = false,
            canUndo = false,
            canRedo = false,
            workspaceButtonState = null,
            workspaceActionHandler = null,
            onAction = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
