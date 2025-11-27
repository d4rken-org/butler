package eu.darken.butler.saver.ui.saver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.net.Uri
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.saver.core.SaveOperation

@Composable
internal fun SaverActionArea(
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
                            text = "${formatFileSize(bytes = saveState.bytesWritten)} / ${saveState.totalBytes?.let { formatFileSize(bytes = it) } ?: "?"}",
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

@Preview2
@Composable
private fun SaverActionAreaIdlePreview() {
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                destination = LocalPath.build("/sdcard/Download"),
                filename = "file.txt",
                sourceInfo = ContentUriHelper.SourceInfo(
                    uri = Uri.parse("content://example/file"),
                    displayName = "file.txt",
                    mimeType = "text/plain",
                    size = 1024,
                    isAccessible = true,
                ),
            ),
            onSave = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaIdleDisabledPreview() {
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(),
            onSave = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaSavingIndeterminatePreview() {
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaveOperation.State.Saving(
                    bytesWritten = 0,
                    totalBytes = null,
                ),
            ),
            onSave = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaSavingWithProgressPreview() {
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaveOperation.State.Saving(
                    bytesWritten = 1_500_000,
                    totalBytes = 3_000_000,
                ),
            ),
            onSave = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaSuccessPreview() {
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaveOperation.State.Success(
                    savedPath = LocalPath.build("/sdcard/Download/file.txt"),
                    bytesWritten = 3_000_000,
                ),
            ),
            onSave = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaErrorPreview() {
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaveOperation.State.Error(SaveOperation.SaveError.WriteError("Disk full")),
                destination = LocalPath.build("/sdcard/Download"),
                filename = "file.txt",
                sourceInfo = ContentUriHelper.SourceInfo(
                    uri = Uri.parse("content://example/file"),
                    displayName = "file.txt",
                    mimeType = "text/plain",
                    size = 1024,
                    isAccessible = true,
                ),
            ),
            onSave = {},
            onOpenSaved = {},
        )
    }
}
