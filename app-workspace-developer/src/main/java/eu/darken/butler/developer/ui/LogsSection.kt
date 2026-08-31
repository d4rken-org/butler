package eu.darken.butler.developer.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.developer.R

@Composable
internal fun LogsSection(
    logs: List<String>,
    isPaused: Boolean,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onTogglePause) {
                Text(
                    text = if (isPaused) {
                        stringResource(R.string.developer_logs_resume)
                    } else {
                        stringResource(R.string.developer_logs_pause)
                    }
                )
            }
            OutlinedButton(onClick = onClear) {
                Text(text = stringResource(R.string.developer_logs_clear))
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.developer_logs_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        } else {
            val horizontalScrollState = rememberScrollState()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState),
                contentPadding = contentPadding,
            ) {
                items(logs) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LogsSectionEmptyPreview() {
    LogsSection(
        logs = emptyList(),
        isPaused = false,
        onTogglePause = {},
        onClear = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LogsSectionWithLogsPreview() {
    LogsSection(
        logs = listOf(
            "2024-01-15 10:23:45.123 D/BUTLER:Explorer: Opening directory /storage/emulated/0",
            "2024-01-15 10:23:45.456 D/BUTLER:Explorer: Found 42 items",
            "2024-01-15 10:23:46.789 I/BUTLER:Workspace: Switched to Explorer workspace",
            "2024-01-15 10:23:47.012 D/BUTLER:IO: Reading file metadata",
            "2024-01-15 10:23:47.345 W/BUTLER:Permissions: Storage permission not granted",
        ),
        isPaused = false,
        onTogglePause = {},
        onClear = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LogsSectionPausedPreview() {
    LogsSection(
        logs = listOf(
            "2024-01-15 10:23:45.123 D/BUTLER:Explorer: Paused log output",
        ),
        isPaused = true,
        onTogglePause = {},
        onClear = {},
    )
}
