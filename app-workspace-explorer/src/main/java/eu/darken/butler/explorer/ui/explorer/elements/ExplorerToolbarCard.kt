package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun ExplorerToolbarCard(
    workspaceId: Workspace.Id,
    modifier: Modifier = Modifier,
    breadcrumbs: List<ExplorerBreadcrumb>,
    design: WorkspaceDesign,
    collapsedFraction: Float = 0f,
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onNavigateToPath: (APath<*>) -> Unit,
    onSetAsHome: ((ExplorerNavigation.Target) -> Unit)? = null,
    onCopyPath: ((String) -> Unit)? = null,
    workspaceButtonState: WorkspaceButtonViewModel.State? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    safLocationManager: SAFLocationManager? = null,
    // Picker mode parameters (all null/default for normal mode)
    pickerSelection: PickerConfig.Selection? = null,
    selectionCount: Int = 0,
    saveAsFilename: String = "",
    canConfirmSelection: Boolean = true,
    onSaveAsFilenameChange: (String) -> Unit = {},
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    val isCollapsed = collapsedFraction > 0.5f
    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) 6.dp else 8.dp,
        label = "cardPadding",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        if (pickerSelection != null) {
            // Picker mode
            PickerToolbarContent(
                breadcrumbs = breadcrumbs,
                isCollapsed = isCollapsed,
                cardPadding = cardPadding,
                pickerSelection = pickerSelection,
                selectionCount = selectionCount,
                saveAsFilename = saveAsFilename,
                canConfirmSelection = canConfirmSelection,
                onSaveAsFilenameChange = onSaveAsFilenameChange,
                onBreadcrumbClick = onBreadcrumbClick,
                onCancel = onCancel,
                onConfirm = onConfirm,
            )
        } else {
            // Normal mode
            NormalToolbarContent(
                workspaceId = workspaceId,
                breadcrumbs = breadcrumbs,
                isCollapsed = isCollapsed,
                cardPadding = cardPadding,
                design = design,
                onBreadcrumbClick = onBreadcrumbClick,
                onNavigateToPath = onNavigateToPath,
                onSetAsHome = onSetAsHome,
                onCopyPath = onCopyPath,
                workspaceButtonState = workspaceButtonState,
                workspaceActionHandler = workspaceActionHandler,
                safLocationManager = safLocationManager,
            )
        }
    }
}

@Composable
private fun NormalToolbarContent(
    workspaceId: Workspace.Id,
    breadcrumbs: List<ExplorerBreadcrumb>,
    isCollapsed: Boolean,
    cardPadding: Dp,
    design: WorkspaceDesign,
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onNavigateToPath: (APath<*>) -> Unit,
    onSetAsHome: ((ExplorerNavigation.Target) -> Unit)?,
    onCopyPath: ((String) -> Unit)?,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceActionHandler: WorkspaceActionHandler?,
    safLocationManager: SAFLocationManager?,
) {
    val workspaceButtonSize by animateDpAsState(
        targetValue = if (isCollapsed) 32.dp else 40.dp,
        label = "workspaceButtonSize",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isCollapsed) {
            // Collapsed state - show icon + path breadcrumbs
            val icon = breadcrumbs.lastOrNull()?.icon ?: Icons.TwoTone.Folder
            val label = getCollapsedBreadcrumbText(breadcrumbs)

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = label ?: stringResource(R.string.explorer_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (label == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }

        } else {
            // Expanded state - full breadcrumb bar
            BreadcrumbBar(
                breadcrumbs = breadcrumbs,
                onBreadcrumbClick = onBreadcrumbClick,
                onNavigateToPath = onNavigateToPath,
                onSetAsHome = onSetAsHome,
                onCopyPath = onCopyPath,
                safLocationManager = safLocationManager,
                showBackground = false,
                modifier = Modifier.weight(1f),
            )
        }

        if (design.isSingle) {
            Spacer(modifier = Modifier.width(8.dp))

            WorkspaceButton(
                state = workspaceButtonState,
                buttonSize = workspaceButtonSize,
                currentWorkspaceId = workspaceId,
                workspaceActionHandler = workspaceActionHandler,
            )
        }
    }
}

@Composable
private fun SaveAsFilenameInput(
    filename: String,
    onFilenameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = filename,
            onValueChange = onFilenameChange,
            label = { Text(stringResource(R.string.explorer_picker_save_as_filename_label)) },
            placeholder = { Text(stringResource(R.string.explorer_picker_save_as_filename_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.TwoTone.Description,
                    contentDescription = null,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (filename.isNotBlank()) onConfirm() }
            ),
        )
    }
}

@Composable
private fun PickerToolbarContent(
    breadcrumbs: List<ExplorerBreadcrumb>,
    isCollapsed: Boolean,
    cardPadding: Dp,
    pickerSelection: PickerConfig.Selection,
    selectionCount: Int,
    saveAsFilename: String,
    canConfirmSelection: Boolean,
    onSaveAsFilenameChange: (String) -> Unit,
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(cardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isCollapsed) {
            // Collapsed picker: [Cancel] | [Icon + Path/Filename] | [Select/Save]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }

                // Center content
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val icon = if (pickerSelection is PickerConfig.Selection.SaveAs) {
                        Icons.TwoTone.Description
                    } else {
                        breadcrumbs.lastOrNull()?.icon ?: Icons.TwoTone.Folder
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val displayText = when (pickerSelection) {
                        is PickerConfig.Selection.SaveAs -> saveAsFilename.ifBlank {
                            stringResource(R.string.explorer_picker_save_as_filename_hint)
                        }
                        else -> getCollapsedBreadcrumbText(breadcrumbs)
                            ?: stringResource(R.string.explorer_loading)
                    }

                    Text(
                        text = displayText,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                FilledTonalButton(
                    onClick = onConfirm,
                    enabled = canConfirmSelection,
                ) {
                    Text(text = getPickerButtonText(pickerSelection, selectionCount))
                }
            }
        } else {
            // Expanded picker: full UI with buttons + optional filename + breadcrumbs

            // Row 1: Cancel + Select buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }

                FilledTonalButton(
                    onClick = onConfirm,
                    enabled = canConfirmSelection,
                ) {
                    Text(text = getPickerButtonText(pickerSelection, selectionCount))
                }
            }

            // Row 2: Filename input for SaveAs mode
            AnimatedVisibility(
                visible = pickerSelection is PickerConfig.Selection.SaveAs,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                SaveAsFilenameInput(
                    filename = saveAsFilename,
                    onFilenameChange = onSaveAsFilenameChange,
                    onConfirm = onConfirm,
                )
            }

            // Row 3: Breadcrumbs
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
            )

            BreadcrumbBar(
                breadcrumbs = breadcrumbs,
                onBreadcrumbClick = onBreadcrumbClick,
                showBackground = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun getCollapsedBreadcrumbText(breadcrumbs: List<ExplorerBreadcrumb>): String? {
    val context = LocalContext.current
    return breadcrumbs
        .mapNotNull { it.label.get(context).takeIf { label -> label.isNotBlank() } }
        .joinToString(" / ")
        .takeIf { it.isNotBlank() }
}

@Composable
private fun getPickerButtonText(selection: PickerConfig.Selection, selectionCount: Int): String {
    return when (selection) {
        is PickerConfig.Selection.DirectorySingle -> stringResource(R.string.explorer_picker_select_this_folder_action)
        is PickerConfig.Selection.SaveAs -> stringResource(R.string.explorer_picker_save_here_action)
        is PickerConfig.Selection.DirectoryMulti,
        is PickerConfig.Selection.MixedMulti -> {
            if (selectionCount > 0) {
                pluralStringResource(R.plurals.explorer_picker_select_count_action, selectionCount, selectionCount)
            } else {
                stringResource(R.string.explorer_picker_select_this_folder_action)
            }
        }
        is PickerConfig.Selection.FileSingle -> stringResource(eu.darken.butler.common.R.string.general_done_action)
        is PickerConfig.Selection.FileMulti -> pluralStringResource(
            R.plurals.explorer_picker_select_count_action,
            selectionCount,
            selectionCount,
        )
    }
}

// region Previews

@Preview2
@Composable
private fun ExplorerToolbarCardExpandedPreview() {
    PreviewWrapper {
        ExplorerToolbarCard(
            workspaceId = Workspace.Id(),
            breadcrumbs = MockDataProvider.createStorageBreadcrumbs(),
            design = WorkspaceDesign(),
            collapsedFraction = 0f,
            onBreadcrumbClick = {},
            onNavigateToPath = {},
            workspaceButtonState = WorkspaceButtonViewModel.State(
                workspaceCount = 1,
                operationsCount = 1,
                attentionCount = 1,
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun ExplorerToolbarCardCollapsedPreview() {
    PreviewWrapper {
        ExplorerToolbarCard(
            workspaceId = Workspace.Id(),
            breadcrumbs = MockDataProvider.createStorageBreadcrumbs(),
            design = WorkspaceDesign(),
            collapsedFraction = 1f,
            onBreadcrumbClick = {},
            onNavigateToPath = {},
            workspaceButtonState = WorkspaceButtonViewModel.State(
                workspaceCount = 1,
                operationsCount = 1,
                attentionCount = 1,
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun ExplorerToolbarCardLoadingPreview() {
    PreviewWrapper {
        ExplorerToolbarCard(
            workspaceId = Workspace.Id(),
            breadcrumbs = emptyList(),
            design = WorkspaceDesign(),
            collapsedFraction = 0f,
            onBreadcrumbClick = {},
            onNavigateToPath = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun ExplorerToolbarCardPickerExpandedPreview() {
    PreviewWrapper {
        ExplorerToolbarCard(
            workspaceId = Workspace.Id(),
            breadcrumbs = MockDataProvider.createDownloadBreadcrumbs(),
            design = WorkspaceDesign(),
            collapsedFraction = 0f,
            onBreadcrumbClick = {},
            onNavigateToPath = {},
            pickerSelection = PickerConfig.Selection.DirectorySingle,
            selectionCount = 0,
            onCancel = {},
            onConfirm = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun ExplorerToolbarCardPickerCollapsedPreview() {
    PreviewWrapper {
        ExplorerToolbarCard(
            workspaceId = Workspace.Id(),
            breadcrumbs = MockDataProvider.createDownloadBreadcrumbs(),
            design = WorkspaceDesign(),
            collapsedFraction = 1f,
            onBreadcrumbClick = {},
            onNavigateToPath = {},
            pickerSelection = PickerConfig.Selection.DirectorySingle,
            selectionCount = 0,
            onCancel = {},
            onConfirm = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun ExplorerToolbarCardSaveAsExpandedPreview() {
    PreviewWrapper {
        ExplorerToolbarCard(
            workspaceId = Workspace.Id(),
            breadcrumbs = MockDataProvider.createDownloadBreadcrumbs(),
            design = WorkspaceDesign(),
            collapsedFraction = 0f,
            onBreadcrumbClick = {},
            onNavigateToPath = {},
            pickerSelection = PickerConfig.Selection.SaveAs(suggestedFilename = "shared_file.pdf"),
            selectionCount = 0,
            saveAsFilename = "shared_file.pdf",
            onSaveAsFilenameChange = {},
            onCancel = {},
            onConfirm = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun ExplorerToolbarCardSaveAsCollapsedPreview() {
    PreviewWrapper {
        ExplorerToolbarCard(
            workspaceId = Workspace.Id(),
            breadcrumbs = MockDataProvider.createDownloadBreadcrumbs(),
            design = WorkspaceDesign(),
            collapsedFraction = 1f,
            onBreadcrumbClick = {},
            onNavigateToPath = {},
            pickerSelection = PickerConfig.Selection.SaveAs(suggestedFilename = "shared_file.pdf"),
            selectionCount = 0,
            saveAsFilename = "shared_file.pdf",
            onSaveAsFilenameChange = {},
            onCancel = {},
            onConfirm = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

// endregion
