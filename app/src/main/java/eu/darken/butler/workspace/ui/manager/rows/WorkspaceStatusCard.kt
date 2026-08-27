package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckBox
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material.icons.twotone.Tab
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceStatusCard(
    workspaceCount: Int,
    operationsCount: Int,
    attentionCount: Int,
    modifier: Modifier = Modifier,
    isOperationsFilterActive: Boolean = false,
    isAttentionFilterActive: Boolean = false,
    selectedCount: Int? = null,
    onTabsClick: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onOperationsClick: () -> Unit = {},
    onAttentionClick: () -> Unit = {},
) {
    val isSelecting = selectedCount != null
    val tabsLabel = if (workspaceCount == 1) {
        stringResource(R.string.workspace_status_tab_singular)
    } else {
        stringResource(R.string.workspace_status_tab_plural)
    }
    val operationsLabel = if (operationsCount == 1) {
        stringResource(R.string.workspace_status_operation_singular)
    } else {
        stringResource(R.string.workspace_status_operation_plural)
    }
    val attentionLabel = stringResource(R.string.workspace_status_attention_label)
    val tabsDesc = stringResource(R.string.workspace_manager_select_all_tabs_desc)
    val selectionDesc = stringResource(R.string.workspace_manager_selection_clear_content_desc)
    val operationsDesc = stringResource(R.string.workspace_status_filter_operations_desc)
    val attentionDesc = stringResource(R.string.workspace_status_filter_attention_desc)

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

        ButlerChip(
            modifier = Modifier.semantics { contentDescription = operationsDesc },
            label = "$operationsCount $operationsLabel",
            leadingIcon = if (isOperationsFilterActive) Icons.TwoTone.CheckCircle else Icons.TwoTone.Sync,
            size = ButlerChipSize.Large,
            enabled = operationsCount > 0 && !isSelecting,
            selected = isOperationsFilterActive,
            colors = if (isOperationsFilterActive) {
                ButlerChipDefaults.highlightColors()
            } else if (operationsCount > 0) {
                ButlerChipDefaults.accentedColors()
            } else {
                ButlerChipDefaults.colors()
            },
            onClick = onOperationsClick,
        )

        ButlerChip(
            modifier = Modifier.semantics { contentDescription = attentionDesc },
            label = "$attentionCount $attentionLabel",
            leadingIcon = if (isAttentionFilterActive) Icons.TwoTone.CheckCircle else Icons.TwoTone.Warning,
            size = ButlerChipSize.Large,
            enabled = attentionCount > 0 && !isSelecting,
            selected = isAttentionFilterActive,
            colors = if (isAttentionFilterActive) {
                ButlerChipDefaults.errorColors()
            } else if (attentionCount > 0) {
                ButlerChipDefaults.accentedColors()
            } else {
                ButlerChipDefaults.colors()
            },
            onClick = onAttentionClick,
        )
    }
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
private fun WorkspaceStatusCardFilterActivePreview() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WorkspaceStatusCard(
            workspaceCount = 5,
            operationsCount = 3,
            attentionCount = 2,
            isOperationsFilterActive = true,
        )
        WorkspaceStatusCard(
            workspaceCount = 5,
            operationsCount = 3,
            attentionCount = 2,
            isAttentionFilterActive = true,
        )
    }
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
            selectedCount = 5,
        )
    }
}
