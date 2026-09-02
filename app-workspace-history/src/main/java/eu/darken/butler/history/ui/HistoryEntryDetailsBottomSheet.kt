package eu.darken.butler.history.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material.icons.twotone.Source
import androidx.compose.material.icons.twotone.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.history.R
import eu.darken.butler.history.core.labelRes
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import kotlin.time.Duration

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryEntryDetailsBottomSheet(
    entry: HistoryEntry?,
    attemptedPaths: List<String> = emptyList(),
    attemptedPathsTotal: Int = 0,
    topInset: Dp = 0.dp,
    bottomInset: Dp,
    onDismiss: () -> Unit,
) {
    PaneScopedBottomSheet(
        visible = entry != null,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
    ) {
        if (entry == null) return@PaneScopedBottomSheet

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetaTag(
                    icon = entry.outcome.icon(),
                    label = entry.outcome.label(),
                    iconTint = entry.outcome.color(),
                )
                MetaTag(
                    icon = entry.kind.icon(),
                    label = entry.kind.label(),
                )
                MetaTag(
                    icon = Icons.TwoTone.Source,
                    label = entry.originType.label(),
                )
                MetaTag(
                    icon = Icons.TwoTone.Schedule,
                    label = formatDuration(entry.duration),
                )
            }

            entry.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entry.partialErrorCount > 0) {
                Callout(
                    icon = Icons.TwoTone.WarningAmber,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    text = pluralStringResource(
                        R.plurals.history_detail_partial_errors_callout,
                        entry.partialErrorCount,
                        entry.partialErrorCount,
                    ),
                )
            }

            entry.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Callout(
                    icon = Icons.TwoTone.Error,
                    iconTint = MaterialTheme.colorScheme.error,
                    text = error,
                )
            }

            Text(
                text = stringResource(R.string.history_detail_label_paths),
                style = MaterialTheme.typography.titleSmall,
            )

            if (entry.paths.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_detail_paths_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (attemptedPaths.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.history_detail_label_attempted_paths),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (attemptedPathsTotal > attemptedPaths.size) {
                        Callout(
                            icon = Icons.TwoTone.WarningAmber,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            text = stringResource(
                                R.string.history_detail_attempted_paths_truncated_callout,
                                attemptedPaths.size,
                                attemptedPathsTotal,
                            ),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        attemptedPaths.forEach { path ->
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                    }
                }
            } else {
                if (entry.pathsTruncated) {
                    Callout(
                        icon = Icons.TwoTone.WarningAmber,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        text = stringResource(
                            R.string.history_detail_paths_truncated_callout,
                            entry.paths.size,
                            entry.affectedPathsCount,
                        ),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    entry.paths.forEach { p ->
                        PathRow(p)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaTag(
    icon: ImageVector,
    label: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Callout(
    icon: ImageVector,
    iconTint: Color,
    text: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PathRow(p: HistoryEntry.PathChange) {
    val previousPath = p.previousPath
    Column(modifier = Modifier.fillMaxWidth()) {
        if (previousPath != null) {
            Text(
                text = previousPath,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = "  → ${p.path}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        } else {
            Text(
                text = "${p.change.name.lowercase()}: ${p.path}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Composable
internal fun HistoryEntry.OriginType.label(): String = stringResource(labelRes)

@Composable
private fun formatDuration(duration: Duration): String {
    val ms = duration.inWholeMilliseconds
    return if (ms < 1000L) {
        stringResource(R.string.history_duration_ms, ms.toInt())
    } else {
        stringResource(R.string.history_duration_seconds, ms / 1000.0f)
    }
}
