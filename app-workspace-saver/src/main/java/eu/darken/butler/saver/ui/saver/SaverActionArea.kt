package eu.darken.butler.saver.ui.saver

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.saver.core.operations.SaveFilesReport
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.OperationEntryRow
import kotlin.time.Clock

@Composable
internal fun SaverActionArea(
    modifier: Modifier = Modifier,
    state: SaverWorkspaceViewModel.State,
    operationDisplay: OperationDisplay?,
    onSave: () -> Unit,
    onOpenSaved: () -> Unit,
    onOperationClick: (Operation.Id) -> Unit = {},
) {
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

            is SaverWorkspace.SaveState.Saving,
            is SaverWorkspace.SaveState.Success,
            is SaverWorkspace.SaveState.Error -> {
                // Show operation progress/result using OperationEntryRow
                operationDisplay?.let { display ->
                    Surface(shape = MaterialTheme.shapes.medium) {
                        OperationEntryRow(
                            operation = display,
                            onRowClick = { onOperationClick(display.id) },
                            isBarExpanded = true,
                        )
                    }
                }

                // Single "Open saved file" button - enabled only on success with files
                val isEnabled = saveState is SaverWorkspace.SaveState.Success &&
                    saveState.report.successes.isNotEmpty()
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isEnabled,
                    onClick = onOpenSaved,
                ) {
                    Text(stringResource(R.string.saver_open_saved_action))
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
            operationDisplay = null,
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
            operationDisplay = null,
            onSave = {},
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
            operationDisplay = OperationDisplay(
                id = Operation.Id(),
                startedAt = Clock.System.now(),
                icon = Icons.TwoTone.Save,
                title = "Saving files".toCaString(),
                description = "Saving to Downloads".toCaString(),
                state = OperationDisplay.State.Running(
                    primaryProgress = Progress.Data(
                        primary = "Saving files".toCaString(),
                        secondary = "photo_003.jpg".toCaString(),
                        count = Progress.Count.Counter(2, 5),
                    ),
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
    val report = SaveFilesReport(
        results = listOf(
            SaveFilesReport.FileResult.Success(
                filename = "file.txt",
                savedPath = LocalPath.build("/sdcard/Download/file.txt"),
                bytes = 1024,
            )
        )
    )
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaverWorkspace.SaveState.Success(report = report),
            ),
            operationDisplay = OperationDisplay(
                id = Operation.Id(),
                startedAt = Clock.System.now(),
                icon = Icons.TwoTone.Save,
                title = "Saving files".toCaString(),
                description = "".toCaString(),
                state = OperationDisplay.State.Completed(
                    summary = report.summary,
                    completedAt = Clock.System.now(),
                    report = report,
                ),
            ),
            onSave = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaBatchSuccessPreview() {
    val report = SaveFilesReport(
        results = (1..5).map { i ->
            SaveFilesReport.FileResult.Success(
                filename = "photo_$i.jpg",
                savedPath = LocalPath.build("/sdcard/Download/photo_$i.jpg"),
                bytes = 1_000_000L * i,
            )
        }
    )
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaverWorkspace.SaveState.Success(report = report),
            ),
            operationDisplay = OperationDisplay(
                id = Operation.Id(),
                startedAt = Clock.System.now(),
                icon = Icons.TwoTone.Save,
                title = "Saving files".toCaString(),
                description = "".toCaString(),
                state = OperationDisplay.State.Completed(
                    summary = report.summary,
                    completedAt = Clock.System.now(),
                    report = report,
                ),
            ),
            onSave = {},
            onOpenSaved = {},
        )
    }
}

@Preview2
@Composable
private fun SaverActionAreaPartialSuccessPreview() {
    val report = SaveFilesReport(
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
    PreviewWrapper {
        SaverActionArea(
            state = SaverWorkspaceViewModel.State(
                saveState = SaverWorkspace.SaveState.Success(report = report),
            ),
            operationDisplay = OperationDisplay(
                id = Operation.Id(),
                startedAt = Clock.System.now(),
                icon = Icons.TwoTone.Save,
                title = "Saving files".toCaString(),
                description = "".toCaString(),
                state = OperationDisplay.State.Completed(
                    summary = report.summary,
                    completedAt = Clock.System.now(),
                    report = report,
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
            operationDisplay = OperationDisplay(
                id = Operation.Id(),
                startedAt = Clock.System.now(),
                icon = Icons.TwoTone.Save,
                title = "Saving files".toCaString(),
                description = "".toCaString(),
                state = OperationDisplay.State.Failed(
                    summary = "Permission denied".toCaString(),
                    completedAt = Clock.System.now(),
                    report = null,
                ),
            ),
            onSave = {},
            onOpenSaved = {},
        )
    }
}
