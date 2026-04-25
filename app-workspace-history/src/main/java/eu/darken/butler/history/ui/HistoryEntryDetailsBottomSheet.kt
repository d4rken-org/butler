package eu.darken.butler.history.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

@Composable
fun HistoryEntryDetailsBottomSheet(
    entry: HistoryEntry?,
    bottomInset: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
) {
    PaneScopedBottomSheet(
        visible = entry != null,
        onDismiss = onDismiss,
        bottomInset = bottomInset,
    ) {
        if (entry == null) return@PaneScopedBottomSheet

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = entry.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            DetailRow(
                stringResource(R.string.history_detail_label_outcome),
                entry.outcome.name,
            )
            DetailRow(
                stringResource(R.string.history_detail_label_origin),
                entry.originType.name,
            )
            DetailRow(
                stringResource(R.string.history_detail_label_duration),
                "${entry.duration.inWholeMilliseconds} ms",
            )
            entry.summary?.let {
                DetailRow(stringResource(R.string.history_detail_label_completed), it)
            }
            if (entry.partialErrorCount > 0) {
                DetailRow(
                    stringResource(R.string.history_detail_label_partial_errors),
                    "${entry.partialErrorCount}",
                )
            }
            entry.errorMessage?.let {
                DetailRow(stringResource(R.string.history_detail_label_error), it)
            }

            Spacer(Modifier.padding(top = 4.dp))
            Text(
                text = stringResource(R.string.history_detail_label_paths),
                style = MaterialTheme.typography.titleSmall,
            )
            if (entry.pathsTruncated) {
                Text(
                    text = stringResource(
                        R.string.history_entry_paths_truncated,
                        entry.paths.size,
                        entry.affectedPathsCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entry.paths, key = { it.path }) { p ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (p.previousPath != null) {
                            Text(
                                text = p.previousPath!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "  → ${p.path}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                text = "${p.change.name.lowercase()}: ${p.path}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.history_detail_close_action))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
