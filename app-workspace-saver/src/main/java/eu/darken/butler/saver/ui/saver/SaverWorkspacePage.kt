package eu.darken.butler.saver.ui.saver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.APath
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.saver.core.SaveOperation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun SaverWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SaverWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SaverWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    SaverWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        workspaceButtonStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
    )
}

@Composable
private fun SaverWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<SaverWorkspaceViewModel.State>,
    workspaceButtonStateSource: Flow<WorkspaceButtonViewModel.State?>,
    vm: SaverWorkspaceViewModel? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
) {
    val state by stateSource.collectAsState(
        initial = SaverWorkspaceViewModel.State()
    )
    val workspaceButtonState by workspaceButtonStateSource.collectAsState(null)

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header with title, subtitle, and WorkspaceButton
            SaverHeader(
                subtitle = state.callerPackage,
                workspaceButtonState = workspaceButtonState,
                workspaceId = workspaceId,
                workspaceActionHandler = workspaceActionHandler,
            )

            // Preview box
            FilePreviewCard(sourceInfo = state.sourceInfo)

            // Source file info card
            SourceFileCard(sourceInfo = state.sourceInfo)

            // Source accessibility warning
            if (state.sourceInfo?.isAccessible == false) {
                WarningCard(
                    message = stringResource(R.string.saver_source_expired_warning),
                    onRetry = { vm?.onRefreshAccessibility() },
                )
            }

            // Destination selector (shows path + filename)
            DestinationCard(
                destination = state.destination,
                filename = state.filename,
                onClick = { vm?.onPickDestination() },
            )

            Spacer(modifier = Modifier.weight(1f))

            // Action area
            ActionArea(
                state = state,
                onSave = { vm?.onSave() },
                onOpenSaved = { vm?.onOpenSavedFile() },
            )
        }
    }
}

@Composable
private fun SaverHeader(
    modifier: Modifier = Modifier,
    subtitle: String?,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceId: Workspace.Id,
    workspaceActionHandler: WorkspaceActionHandler?,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.saver_workspace_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            subtitle?.let {
                Text(
                    text = stringResource(R.string.saver_shared_from, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WorkspaceButton(
            state = workspaceButtonState,
            currentWorkspaceId = workspaceId,
            workspaceActionHandler = workspaceActionHandler,
        )
    }
}

@Composable
private fun FilePreviewCard(
    modifier: Modifier = Modifier,
    sourceInfo: ContentUriHelper.SourceInfo?,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.saver_preview_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (sourceInfo != null) {
                    val isImage = sourceInfo.mimeType?.startsWith("image/") == true
                    if (isImage) {
                        AsyncImage(
                            model = sourceInfo.uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(
                            modifier = Modifier.size(64.dp),
                            imageVector = Icons.TwoTone.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun SourceFileCard(
    modifier: Modifier = Modifier,
    sourceInfo: ContentUriHelper.SourceInfo?,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                modifier = Modifier.size(48.dp),
                imageVector = Icons.TwoTone.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sourceInfo?.displayName ?: stringResource(R.string.saver_loading),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sourceInfo != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sourceInfo.size?.let { size ->
                            Text(
                                text = formatFileSize(size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        sourceInfo.mimeType?.let { mimeType ->
                            Text(
                                text = mimeType,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (sourceInfo?.isAccessible == true) {
                Icon(
                    imageVector = Icons.TwoTone.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else if (sourceInfo?.isAccessible == false) {
                Icon(
                    imageVector = Icons.TwoTone.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun WarningCard(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.TwoTone.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                modifier = Modifier.weight(1f),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(onClick = onRetry) {
                Text(stringResource(R.string.saver_retry_action))
            }
        }
    }
}

@Composable
private fun DestinationCard(
    modifier: Modifier = Modifier,
    destination: APath<*>?,
    filename: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.TwoTone.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.saver_destination_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (destination != null && filename.isNotBlank()) {
                    // Show full path with filename
                    Text(
                        text = "${destination.userReadablePath.get(context)}/$filename",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    // Prompt to select destination
                    Text(
                        text = stringResource(R.string.saver_select_destination_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionArea(
    modifier: Modifier = Modifier,
    state: SaverWorkspaceViewModel.State,
    onSave: () -> Unit,
    onOpenSaved: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val saveState = state.saveState) {
            is SaveOperation.State.Idle -> {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSave,
                    enabled = state.canSave,
                ) {
                    Text(stringResource(R.string.saver_save_action))
                }
            }

            is SaveOperation.State.Saving -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.saver_saving_progress),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (saveState.progress != null) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = { saveState.progress!! },
                        )
                        Text(
                            text = "${formatFileSize(saveState.bytesWritten)} / ${saveState.totalBytes?.let { formatFileSize(it) } ?: "?"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            is SaveOperation.State.Success -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        modifier = Modifier.size(48.dp),
                        imageVector = Icons.TwoTone.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.saver_success_message),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenSaved,
                    ) {
                        Text(stringResource(R.string.saver_open_saved_action))
                    }
                }
            }

            is SaveOperation.State.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        modifier = Modifier.size(48.dp),
                        imageVector = Icons.TwoTone.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = when (saveState.error) {
                            is SaveOperation.SaveError.SourceExpired ->
                                stringResource(R.string.saver_error_source_expired)
                            is SaveOperation.SaveError.PermissionDenied ->
                                stringResource(R.string.saver_error_permission_denied)
                            is SaveOperation.SaveError.WriteError ->
                                stringResource(R.string.saver_error_write_failed)
                            is SaveOperation.SaveError.FileExists ->
                                stringResource(R.string.saver_error_file_exists)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSave,
                        enabled = state.canSave,
                    ) {
                        Text(stringResource(R.string.saver_retry_action))
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Preview2
@Composable
private fun SaverWorkspacePagePreview() {
    PreviewWrapper {
        SaverWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(
                SaverWorkspaceViewModel.State(
                    filename = "image.jpg",
                    callerPackage = "org.telegram.messenger",
                )
            ),
            workspaceButtonStateSource = flowOf(null),
        )
    }
}
