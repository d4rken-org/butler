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

/**
 * Renders only the chips for filter values currently SET, plus a trailing `+ Add filter` chip
 * that opens the [HistoryAddFilterSheet]. When the filter is fully empty, only the Add chip
 * is visible — by design, the toolbar starts uncluttered.
 */
@Composable
fun HistoryFilterChips(
    modifier: Modifier = Modifier,
    cutoutWidth: Dp = 0.dp,
    cutoutHeight: Dp = 0.dp,
    filter: HistoryFilter,
    onRemoveOutcome: (HistoryOutcome) -> Unit,
    onRemoveKind: (Operation.Metadata.Kind) -> Unit,
    onRemovePathScope: (String) -> Unit,
    onAddFilter: () -> Unit,
) {
    CutoutAwareFlowRow(
        modifier = modifier.fillMaxWidth(),
        cutoutWidth = cutoutWidth,
        cutoutHeight = cutoutHeight,
        horizontalSpacing = 6.dp,
        verticalSpacing = 6.dp,
    ) {
        filter.outcomes.forEach { outcome ->
            ButlerChip(
                label = outcome.label(),
                selected = true,
                onRemove = { onRemoveOutcome(outcome) },
            )
        }
        filter.kinds.forEach { kind ->
            ButlerChip(
                label = kind.label(),
                selected = true,
                onRemove = { onRemoveKind(kind) },
                colors = ButlerChipDefaults.accentedColors(),
            )
        }
        filter.pathScopes.forEach { scope ->
            ButlerChip(
                modifier = Modifier.widthIn(max = 320.dp),
                label = scope,
                leadingIcon = Icons.TwoTone.Folder,
                selected = true,
                onRemove = { onRemovePathScope(scope) },
            )
        }
        ButlerChip(
            label = stringResource(R.string.history_add_filter_action),
            leadingIcon = Icons.TwoTone.Add,
            onClick = onAddFilter,
        )
    }
}

@Composable
internal fun HistoryOutcome.label(): String = stringResource(
    when (this) {
        HistoryOutcome.COMPLETED -> R.string.history_filter_outcome_completed
        HistoryOutcome.PARTIAL -> R.string.history_filter_outcome_partial
        HistoryOutcome.FAILED -> R.string.history_filter_outcome_failed
        HistoryOutcome.CANCELLED -> R.string.history_filter_outcome_cancelled
    }
)

@Composable
internal fun Operation.Metadata.Kind.label(): String = stringResource(
    when (this) {
        Operation.Metadata.Kind.COPY -> R.string.history_filter_kind_copy
        Operation.Metadata.Kind.MOVE -> R.string.history_filter_kind_move
        Operation.Metadata.Kind.DELETE -> R.string.history_filter_kind_delete
        Operation.Metadata.Kind.CREATE_FILE -> R.string.history_filter_kind_create_file
        Operation.Metadata.Kind.CREATE_FOLDER -> R.string.history_filter_kind_create_folder
        Operation.Metadata.Kind.SAVE -> R.string.history_filter_kind_save
        Operation.Metadata.Kind.COMPRESS -> R.string.history_filter_kind_compress
        Operation.Metadata.Kind.EXTRACT -> R.string.history_filter_kind_extract
        Operation.Metadata.Kind.RESTORE -> R.string.history_filter_kind_restore
        Operation.Metadata.Kind.INSTALL -> R.string.history_filter_kind_install
    }
)
