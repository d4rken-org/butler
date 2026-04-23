package eu.darken.butler.workspace.ui.manager.rows.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

private data class LogEntry(
    val timestamp: String,
    val level: String,
    val message: String,
)

private val sampleLogs = listOf(
    LogEntry("12:34:56.789", "D", "Workspace initialized"),
    LogEntry("12:34:56.801", "I", "Loading test data..."),
    LogEntry("12:34:56.823", "W", "Cache miss"),
    LogEntry("12:34:56.845", "E", "Connection timeout"),
)

@Composable
fun DeveloperMockPreview(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        sampleLogs.forEach { entry ->
            DeveloperLogRow(
                timestamp = entry.timestamp,
                level = entry.level,
                message = entry.message,
            )
        }
    }
}

@Composable
private fun DeveloperLogRow(
    timestamp: String,
    level: String,
    message: String,
) {
    val levelColor = when (level) {
        "D" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        "I" -> Color(0xFF2196F3).copy(alpha = 0.7f)
        "W" -> Color(0xFFFFC107).copy(alpha = 0.8f)
        "E" -> Color(0xFFF44336).copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = timestamp,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Text(
            text = level,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = levelColor,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 9.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeveloperMockPreviewPreview() {
    DeveloperMockPreview()
}
