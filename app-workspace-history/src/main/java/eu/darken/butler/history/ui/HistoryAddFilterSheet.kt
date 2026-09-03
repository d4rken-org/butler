package eu.darken.butler.history.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.common.R as CommonR

/**
 * Add-filter sheet — three live-update sections (Outcome / Kind / Path scope), all chips visible.
 * Tapping a chip toggles the value in/out of the filter (parent VM updates immediately). Sheet
 * stays open until back/drag/tap-outside; no Done button. The header offers a reset that drops
 * every dimension back to unfiltered.
 *
 * Path scope row offers an "+ Add path…" entry that closes the sheet and opens the existing
 * [PathScopeDialog] (we surface that dialog at the page level since stacking dialog over sheet is
 * visually awkward).
 */
@Composable
fun HistoryAddFilterSheet(
    visible: Boolean,
    filter: HistoryFilter,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    onDismiss: () -> Unit,
    onResetFilter: () -> Unit,
    onToggleOutcome: (HistoryOutcome) -> Unit,
    onToggleKind: (Operation.Metadata.Kind) -> Unit,
    onRemovePathScope: (String) -> Unit,
    onAddPathScopeRequested: () -> Unit,
) {
    PaneScopedBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.history_add_filter_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = onResetFilter,
                    enabled = !filter.isUnfiltered,
                ) {
                    Text(stringResource(CommonR.string.general_reset_action))
                }
            }

            FilterSection(
                title = stringResource(R.string.history_add_filter_section_outcome),
            ) {
                HistoryOutcome.entries.forEach { outcome ->
                    val selected = outcome in filter.outcomes
                    ButlerChip(
                        label = outcome.label(),
                        leadingIcon = if (selected) Icons.TwoTone.Check else null,
                        selected = selected,
                        onClick = { onToggleOutcome(outcome) },
                    )
                }
            }

            FilterSection(
                title = stringResource(R.string.history_add_filter_section_kind),
            ) {
                Operation.Metadata.Kind.entries.forEach { kind ->
                    val selected = kind in filter.kinds
                    ButlerChip(
                        label = kind.label(),
                        leadingIcon = if (selected) Icons.TwoTone.Check else null,
                        selected = selected,
                        onClick = { onToggleKind(kind) },
                    )
                }
            }

            PathScopeSection(
                pathScopes = filter.pathScopes,
                onRemovePathScope = onRemovePathScope,
                onAddPathScopeRequested = onAddPathScopeRequested,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PathScopeSection(
    pathScopes: Set<String>,
    onRemovePathScope: (String) -> Unit,
    onAddPathScopeRequested: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.history_add_filter_section_path_scope),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        if (pathScopes.isNotEmpty()) {
            // Cap height + scroll so the sheet doesn't blow out with many paths.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(pathScopes.toList(), key = { it }) { scope ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.TwoTone.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = scope,
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(max = 320.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
                            )
                            TextButton(onClick = { onRemovePathScope(scope) }) {
                                Text(stringResource(R.string.history_path_scope_remove_action))
                            }
                        }
                    }
                }
            }
        }
        ButlerChip(
            modifier = Modifier.wrapContentSize(),
            label = stringResource(R.string.history_add_filter_add_path_action),
            leadingIcon = Icons.TwoTone.Add,
            onClick = onAddPathScopeRequested,
        )
    }
}
