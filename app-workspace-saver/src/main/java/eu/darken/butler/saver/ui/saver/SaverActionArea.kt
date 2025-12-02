package eu.darken.butler.saver.ui.saver

import android.net.Uri
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.saver.core.operations.SaveFilesReport

@Composable
internal fun SaverActionArea(
    modifier: Modifier = Modifier,
    state: SaverWorkspaceViewModel.State,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onOpenSaved: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val saveState = state.saveState) {
            is SaverWorkspace.SaveState.Idle -> {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSave,
                    enabled = state.canSave,
                ) {
                    Text(stringResource(R.string.saver_save_action))
                }
            }

            is SaverWorkspace.SaveState.Saving -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.saver_saving_progress),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = {
                            if (saveState.totalFiles > 0) {
                                saveState.currentFile.toFloat() / saveState.totalFiles
                            } else {
                                0f
                            }
                        },
                    )
                    if (saveState.totalFiles > 1) {
                        Text(
                            text = "${saveState.currentFile} / ${saveState.totalFiles}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (saveState.currentFilename.isNotBlank()) {
                        Text(
                            text = saveState.currentFilename,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is SaverWorkspace.SaveState.Success -> {
                val report = saveState.report
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
                        text = when {
                            report.errors.isEmpty() -> context.getQuantityString2(
                                R.plurals.saver_success_count,
                                report.successes.size,
                                report.successes.size,
                            )
                            report.successes.isEmpty() -> context.getQuantityString2(
                                R.plurals.saver_error_count,
                                report.errors.size,
                                report.errors.size,
                            )
                            else -> context.getString(
                                R.string.saver_partial_success,
                                report.successes.size,
                                report.results.size,
                            )
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (report.successes.isNotEmpty()) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onOpenSaved,
                        ) {
                            Text(stringResource(R.string.saver_open_saved_action))
                        }
                    }
                    if (report.errors.isNotEmpty()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onRetry,
                        ) {
                            Text(stringResource(R.string.saver_retry_action))
                        }
                    }
                }
            }

            is SaverWorkspace.SaveState.Error -> {
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
                        text = stringResource(R.string.saver_error_write_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRetry,
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
                sourceInfos = listOf(
                    ContentUriHelper.SourceInfo(
                        uri = Uri.parse("content://example/file"),
                        displayName = "file.txt",
                        mimeType = "text/plain",
                        size = 1024,
                        isAccessible = true,
                    )
                ),
            ),
            onSave = {},
            onRetry = {},
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
            onRetry = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaSavingPreview() {
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaverWorkspace.SaveState.Saving(
                    currentFile = 2,
                    totalFiles = 5,
                    currentFilename = "photo_003.jpg",
                ),
            ),
            onSave = {},
            onRetry = {},
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
                saveState = SaverWorkspace.SaveState.Success(
                    report = SaveFilesReport(
                        results = listOf(
                            SaveFilesReport.FileResult.Success(
                                filename = "file.txt",
                                savedPath = LocalPath.build("/sdcard/Download/file.txt"),
                                bytes = 1024,
                            )
                        )
                    )
                ),
            ),
            onSave = {},
            onRetry = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaBatchSuccessPreview() {
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaverWorkspace.SaveState.Success(
                    report = SaveFilesReport(
                        results = (1..5).map { i ->
                            SaveFilesReport.FileResult.Success(
                                filename = "photo_$i.jpg",
                                savedPath = LocalPath.build("/sdcard/Download/photo_$i.jpg"),
                                bytes = 1_000_000L * i,
                            )
                        }
                    )
                ),
            ),
            onSave = {},
            onRetry = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaPartialSuccessPreview() {
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaverWorkspace.SaveState.Success(
                    report = SaveFilesReport(
                        results = listOf(
                            SaveFilesReport.FileResult.Success(
                                filename = "photo_1.jpg",
                                savedPath = LocalPath.build("/sdcard/Download/photo_1.jpg"),
                                bytes = 1_000_000,
                            ),
                            SaveFilesReport.FileResult.Error(
                                filename = "photo_2.jpg",
                                error = SecurityException("Permission denied"),
                            ),
                        )
                    )
                ),
            ),
            onSave = {},
            onRetry = {},
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
                saveState = SaverWorkspace.SaveState.Error(SecurityException("Permission denied")),
                destination = LocalPath.build("/sdcard/Download"),
                filename = "file.txt",
                sourceInfos = listOf(
                    ContentUriHelper.SourceInfo(
                        uri = Uri.parse("content://example/file"),
                        displayName = "file.txt",
                        mimeType = "text/plain",
                        size = 1024,
                        isAccessible = true,
                    )
                ),
            ),
            onSave = {},
            onRetry = {},
            onOpenSaved = {},
        )
    }
}
