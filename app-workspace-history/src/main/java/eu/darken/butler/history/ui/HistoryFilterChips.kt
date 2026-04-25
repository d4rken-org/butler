package eu.darken.butler.history.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome

@Composable
fun HistoryFilterChips(
    modifier: Modifier = Modifier,
    filter: HistoryFilter,
    onToggleOutcome: (HistoryOutcome) -> Unit,
    onToggleKind: (Operation.Metadata.Kind) -> Unit,
    onClearPathScope: () -> Unit,
    onSetPathScope: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Outcome row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryOutcome.entries.forEach { outcome ->
                ButlerChip(
                    label = outcome.label(),
                    selected = outcome in filter.outcomes,
                    onClick = { onToggleOutcome(outcome) },
                )
            }
        }

        // Kind row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Operation.Metadata.Kind.entries.forEach { kind ->
                ButlerChip(
                    label = kind.label(),
                    selected = kind in filter.kinds,
                    onClick = { onToggleKind(kind) },
                )
            }
        }

        // Path scope row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filter.pathScope != null) {
                ButlerChip(
                    label = filter.pathScope!!,
                    selected = true,
                    onClick = onSetPathScope,
                    onRemove = onClearPathScope,
                )
            } else {
                ButlerChip(
                    label = stringResource(R.string.history_path_scope_set_action),
                    onClick = onSetPathScope,
                )
            }
        }
    }
}

@Composable
private fun HistoryOutcome.label(): String = stringResource(
    when (this) {
        HistoryOutcome.COMPLETED -> R.string.history_filter_outcome_completed
        HistoryOutcome.PARTIAL -> R.string.history_filter_outcome_partial
        HistoryOutcome.FAILED -> R.string.history_filter_outcome_failed
        HistoryOutcome.CANCELLED -> R.string.history_filter_outcome_cancelled
    }
)

@Composable
private fun Operation.Metadata.Kind.label(): String = stringResource(
    when (this) {
        Operation.Metadata.Kind.COPY -> R.string.history_filter_kind_copy
        Operation.Metadata.Kind.MOVE -> R.string.history_filter_kind_move
        Operation.Metadata.Kind.DELETE -> R.string.history_filter_kind_delete
        Operation.Metadata.Kind.CREATE_FILE -> R.string.history_filter_kind_create_file
        Operation.Metadata.Kind.CREATE_FOLDER -> R.string.history_filter_kind_create_folder
        Operation.Metadata.Kind.SAVE -> R.string.history_filter_kind_save
    }
)
