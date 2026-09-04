package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckBox
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Pause
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material.icons.twotone.Tab
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipColors
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerFilter
import eu.darken.butler.workspace.R as WorkspaceR

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceStatusCard(
    workspaceCount: Int,
    operationsCount: Int,
    attentionCount: Int,
    modifier: Modifier = Modifier,
    pausedCount: Int = 0,
    unsavedCount: Int = 0,
    activeFilter: WorkspaceManagerFilter? = null,
    selectedCount: Int? = null,
    onTabsClick: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onFilterClick: (WorkspaceManagerFilter) -> Unit = {},
) {
    val isSelecting = selectedCount != null
    val tabsLabel = if (workspaceCount == 1) {
        stringResource(R.string.workspace_status_tab_singular)
    } else {
        stringResource(R.string.workspace_status_tab_plural)
    }
    val tabsDesc = stringResource(R.string.workspace_manager_select_all_tabs_desc)
    val selectionDesc = stringResource(R.string.workspace_manager_selection_clear_content_desc)

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Tapping the count selects every tab, matching how the other workspaces' count chips
        // behave. While selecting, the same slot reports the selection and only its trailing X
        // clears it - putting clear on the chip body would wipe a hand-picked set on a stray tap.
        if (isSelecting) {
            ButlerChip(
                modifier = Modifier.semantics { contentDescription = selectionDesc },
                label = stringResource(R.string.workspace_manager_selection_count, selectedCount),
                leadingIcon = Icons.TwoTone.CheckBox,
                size = ButlerChipSize.Large,
                colors = ButlerChipDefaults.highlightColors(),
                onRemove = onClearSelection,
            )
        } else {
            ButlerChip(
                modifier = Modifier.semantics { contentDescription = tabsDesc },
                label = "$workspaceCount $tabsLabel",
                leadingIcon = Icons.TwoTone.Tab,
                size = ButlerChipSize.Large,
                enabled = workspaceCount > 0,
                onClick = onTabsClick,
            )
        }

        WorkspaceManagerFilter.entries.forEach { filter ->
            val count = when (filter) {
                WorkspaceManagerFilter.OPERATIONS -> operationsCount
                WorkspaceManagerFilter.ATTENTION -> attentionCount
                WorkspaceManagerFilter.PAUSED -> pausedCount
                WorkspaceManagerFilter.UNSAVED -> unsavedCount
            }
            val isActive = activeFilter == filter
            // A facet with nothing to show is left out rather than shown dead: with four of them the
            // row would otherwise open on a wall of zeroes. The active one stays regardless, so the
            // chip holding the filter can never vanish under the tap that emptied it and strand the
            // user on an empty grid with nothing left to clear.
            if (count == 0 && !isActive) return@forEach
            // Keyed by facet, not by position: a chip that drops out shifts every later one, and
            // without a key they would inherit each other's slot.
            key(filter) {
                val filterDesc = filter.filterDescription()
                ButlerChip(
                    modifier = Modifier.semantics { contentDescription = filterDesc },
                    label = "$count ${filter.label(count)}",
                    leadingIcon = filter.icon,
                    size = ButlerChipSize.Default,
                    enabled = !isSelecting,
                    selected = isActive,
                    colors = filter.colors(isActive),
                    onClick = { onFilterClick(filter) },
                )
            }
        }
    }
}

private val WorkspaceManagerFilter.icon: ImageVector
    get() = when (this) {
        WorkspaceManagerFilter.OPERATIONS -> Icons.TwoTone.Sync
        WorkspaceManagerFilter.ATTENTION -> Icons.TwoTone.Warning
        WorkspaceManagerFilter.PAUSED -> Icons.TwoTone.Pause
        // Same icon the card's own unsaved marker uses, so the chip and the badge read as one thing.
        WorkspaceManagerFilter.UNSAVED -> Icons.TwoTone.Edit
    }

@Composable
private fun WorkspaceManagerFilter.label(count: Int): String = when (this) {
    WorkspaceManagerFilter.OPERATIONS -> if (count == 1) {
        stringResource(R.string.workspace_status_operation_singular)
    } else {
        stringResource(R.string.workspace_status_operation_plural)
    }
    WorkspaceManagerFilter.ATTENTION -> stringResource(R.string.workspace_status_attention_label)
    // Same word the card's own paused overlay uses, so both come from one translation.
    WorkspaceManagerFilter.PAUSED -> stringResource(WorkspaceR.string.workspace_paused_label)
    WorkspaceManagerFilter.UNSAVED -> stringResource(R.string.workspace_status_unsaved_label)
}

@Composable
private fun WorkspaceManagerFilter.filterDescription(): String = when (this) {
    WorkspaceManagerFilter.OPERATIONS -> stringResource(R.string.workspace_status_filter_operations_desc)
    WorkspaceManagerFilter.ATTENTION -> stringResource(R.string.workspace_status_filter_attention_desc)
    WorkspaceManagerFilter.PAUSED -> stringResource(R.string.workspace_status_filter_paused_desc)
    WorkspaceManagerFilter.UNSAVED -> stringResource(R.string.workspace_status_filter_unsaved_desc)
}

/**
 * Error red is reserved for attention, the only facet describing a fault. An unsaved edit and a
 * paused tab are working states, and their cards say so too: a tertiary pencil and a plain grey
 * "Paused" label rather than the attention glow.
 */
@Composable
private fun WorkspaceManagerFilter.colors(isActive: Boolean): ButlerChipColors = when {
    isActive && this == WorkspaceManagerFilter.ATTENTION -> ButlerChipDefaults.errorColors()
    isActive -> ButlerChipDefaults.highlightColors()
    this == WorkspaceManagerFilter.PAUSED -> ButlerChipDefaults.colors()
    else -> ButlerChipDefaults.accentedColors()
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceStatusCardPreview() {
    WorkspaceStatusCard(
        workspaceCount = 5,
        operationsCount = 3,
        attentionCount = 2,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceStatusCardEmptyPreview() {
    WorkspaceStatusCard(
        workspaceCount = 1,
        operationsCount = 0,
        attentionCount = 0,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceStatusCardAllFacetsPreview() {
    WorkspaceStatusCard(
        workspaceCount = 8,
        operationsCount = 3,
        attentionCount = 2,
        pausedCount = 4,
        unsavedCount = 1,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceStatusCardFilterActivePreview() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WorkspaceManagerFilter.entries.forEach { filter ->
            WorkspaceStatusCard(
                workspaceCount = 8,
                operationsCount = 3,
                attentionCount = 2,
                pausedCount = 4,
                unsavedCount = 1,
                activeFilter = filter,
            )
        }
    }
}

/** The active facet stays in the row after its count drops to zero, so it can still be cleared. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceStatusCardEmptiedFilterPreview() {
    WorkspaceStatusCard(
        workspaceCount = 5,
        operationsCount = 0,
        attentionCount = 0,
        activeFilter = WorkspaceManagerFilter.OPERATIONS,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceStatusCardSelectionPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WorkspaceStatusCard(
            workspaceCount = 5,
            operationsCount = 3,
            attentionCount = 2,
            selectedCount = 2,
        )
        WorkspaceStatusCard(
            workspaceCount = 5,
            operationsCount = 3,
            attentionCount = 2,
            pausedCount = 4,
            unsavedCount = 1,
            selectedCount = 5,
        )
    }
}
