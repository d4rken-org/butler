package eu.darken.butler.history.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.ui.common.CutoutAwareFlowRow

@Composable
fun HistoryFilterChips(
    modifier: Modifier = Modifier,
    cutoutWidth: Dp = 0.dp,
    cutoutHeight: Dp = 0.dp,
    filter: HistoryFilter,
    onToggleOutcome: (HistoryOutcome) -> Unit,
    onToggleKind: (Operation.Metadata.Kind) -> Unit,
    onClearPathScope: () -> Unit,
    onSetPathScope: () -> Unit,
) {
    CutoutAwareFlowRow(
        modifier = modifier.fillMaxWidth(),
        cutoutWidth = cutoutWidth,
        cutoutHeight = cutoutHeight,
        horizontalSpacing = 6.dp,
        verticalSpacing = 6.dp,
    ) {
        HistoryOutcome.entries.forEach { outcome ->
            ButlerChip(
                label = outcome.label(),
                selected = outcome in filter.outcomes,
                onClick = { onToggleOutcome(outcome) },
            )
        }
        Operation.Metadata.Kind.entries.forEach { kind ->
            ButlerChip(
                label = kind.label(),
                selected = kind in filter.kinds,
                onClick = { onToggleKind(kind) },
                colors = ButlerChipDefaults.accentedColors(),
            )
        }
        val scope = filter.pathScope
        if (scope != null) {
            ButlerChip(
                modifier = Modifier.widthIn(max = 320.dp),
                label = scope,
                leadingIcon = Icons.TwoTone.Folder,
                selected = true,
                onClick = onSetPathScope,
                onRemove = onClearPathScope,
            )
        } else {
            ButlerChip(
                label = stringResource(R.string.history_path_scope_set_action),
                leadingIcon = Icons.TwoTone.Add,
                onClick = onSetPathScope,
            )
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
