package eu.darken.butler.common.debug.recorder.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun ShortRecordingDialog(
    onKeepRecording: () -> Unit,
    onStopAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepRecording,
        title = {
            Text(
                text = stringResource(R.string.debug_log_short_recording_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.debug_log_short_recording_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onStopAnyway) {
                Text(stringResource(R.string.debug_log_stop_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepRecording) {
                Text(stringResource(R.string.debug_log_short_recording_keep_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShortRecordingDialogPreview() {
    ShortRecordingDialog(
        onKeepRecording = {},
        onStopAnyway = {},
    )
}
