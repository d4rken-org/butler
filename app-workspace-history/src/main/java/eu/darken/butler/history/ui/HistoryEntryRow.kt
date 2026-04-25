package eu.darken.butler.history.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.DriveFileMove
import androidx.compose.material.icons.automirrored.twotone.NoteAdd
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.CopyAll
import androidx.compose.material.icons.twotone.CreateNewFolder
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.ErrorOutline
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun HistoryEntryRow(
    modifier: Modifier = Modifier,
    entry: HistoryEntry,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = entry.outcome.icon(),
                contentDescription = entry.outcome.name,
                tint = entry.outcome.color(),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.headline(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = entry.subline(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = entry.kind.icon(),
                    contentDescription = entry.kind.name,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = if (entry.pathsTruncated) {
                        stringResource(
                            R.string.history_entry_paths_truncated,
                            entry.paths.size,
                            entry.affectedPathsCount,
                        )
                    } else {
                        "${entry.affectedPathsCount}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun HistoryEntry.headline(): String {
    val intentLabel = when (intent) {
        Operation.Metadata.Intent.RENAME -> "Renamed"
        Operation.Metadata.Intent.PASTE_COPY -> "Pasted"
        Operation.Metadata.Intent.PASTE_MOVE -> "Pasted (move)"
        null -> kind.label()
    }
    val target = paths.firstOrNull()?.path?.substringAfterLast('/')
    return if (target.isNullOrBlank()) intentLabel else "$intentLabel  $target"
}

private fun HistoryEntry.subline(): String {
    val originLabel = originType.name.lowercase().replaceFirstChar { it.uppercaseChar() }
    val timeAgo = formatTimeAgo(Clock.System.now() - completedAt)
    val pathHint = paths.firstOrNull()?.path?.substringBeforeLast('/').orEmpty()
    return if (pathHint.isNotEmpty()) "$originLabel  ·  $timeAgo  ·  $pathHint" else "$originLabel  ·  $timeAgo"
}

private fun Operation.Metadata.Kind.label(): String = when (this) {
    Operation.Metadata.Kind.COPY -> "Copied"
    Operation.Metadata.Kind.MOVE -> "Moved"
    Operation.Metadata.Kind.DELETE -> "Deleted"
    Operation.Metadata.Kind.CREATE_FOLDER -> "Created folder"
    Operation.Metadata.Kind.CREATE_FILE -> "Created file"
    Operation.Metadata.Kind.SAVE -> "Saved"
}

private fun Operation.Metadata.Kind.icon(): ImageVector = when (this) {
    Operation.Metadata.Kind.COPY -> Icons.TwoTone.CopyAll
    Operation.Metadata.Kind.MOVE -> Icons.AutoMirrored.TwoTone.DriveFileMove
    Operation.Metadata.Kind.DELETE -> Icons.TwoTone.Delete
    Operation.Metadata.Kind.CREATE_FOLDER -> Icons.TwoTone.CreateNewFolder
    Operation.Metadata.Kind.CREATE_FILE -> Icons.AutoMirrored.TwoTone.NoteAdd
    Operation.Metadata.Kind.SAVE -> Icons.TwoTone.Save
}

@Composable
private fun HistoryOutcome.icon(): ImageVector = when (this) {
    HistoryOutcome.COMPLETED -> Icons.TwoTone.CheckCircle
    HistoryOutcome.PARTIAL -> Icons.TwoTone.ErrorOutline
    HistoryOutcome.FAILED -> Icons.TwoTone.Error
    HistoryOutcome.CANCELLED -> Icons.TwoTone.Cancel
}

@Composable
private fun HistoryOutcome.color(): Color = when (this) {
    HistoryOutcome.COMPLETED -> MaterialTheme.colorScheme.primary
    HistoryOutcome.PARTIAL -> MaterialTheme.colorScheme.tertiary
    HistoryOutcome.FAILED -> MaterialTheme.colorScheme.error
    HistoryOutcome.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatTimeAgo(d: Duration): String = when {
    d < 1.minutes -> "just now"
    d < 60.minutes -> "${d.inWholeMinutes}m"
    d < 1.minutes * 60 * 24 -> "${d.inWholeHours}h"
    else -> "${d.inWholeDays}d"
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryEntryRowPreview() {
    val now = Clock.System.now()
    HistoryEntryRow(
        entry = HistoryEntry(
            id = "1",
            kind = Operation.Metadata.Kind.COPY,
            intent = Operation.Metadata.Intent.PASTE_COPY,
            originType = HistoryEntry.OriginType.EXPLORER,
            originWorkspaceId = "abc",
            title = "Copy 5 items",
            description = "5 items to /backup",
            summary = "5 files copied",
            startedAt = now - 30.seconds,
            completedAt = now - 5.seconds,
            duration = 25.seconds,
            outcome = HistoryOutcome.COMPLETED,
            errorMessage = null,
            errorClass = null,
            affectedPathsCount = 5,
            partialErrorCount = 0,
            pathsTruncated = false,
            paths = listOf(
                HistoryEntry.PathChange(
                    path = "/storage/emulated/0/backup/photo1.jpg",
                    previousPath = null,
                    change = Operation.Report.PathChange.Change.ADDED,
                ),
            ),
        ),
        onClick = {},
    )
}
