package eu.darken.butler.history.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.History
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.ui.common.CutoutCard
import eu.darken.butler.workspace.ui.common.CutoutCardDefaults
import eu.darken.butler.workspace.ui.common.CutoutMode
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun HistoryToolbarCard(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    filter: HistoryFilter,
    entryCount: Int,
    totalCount: Int,
    collapsedFraction: Float = 0f,
    onToggleOutcome: (HistoryOutcome) -> Unit,
    onToggleKind: (Operation.Metadata.Kind) -> Unit,
    onSetPathScope: () -> Unit,
    onClearPathScope: () -> Unit,
    onClearFilter: () -> Unit,
    onClearAllRequested: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val isCollapsed = collapsedFraction > 0.5f
    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) {
            CutoutCardDefaults.ContentPaddingCollapsed
        } else {
            CutoutCardDefaults.ContentPaddingExpanded
        },
        label = "historyToolbarCardPadding",
    )

    CutoutCard(
        modifier = modifier.fillMaxWidth(),
        cutoutContent = if (design.isSingle) {
            {
                WorkspaceButton(
                    currentWorkspaceId = workspaceId,
                    buttonSize = if (isCollapsed) {
                        WorkspaceButtonDefaults.sizeCompact
                    } else {
                        WorkspaceButtonDefaults.sizeDefault
                    },
                )
            }
        } else {
            null
        },
        cutoutMode = if (isCollapsed) CutoutMode.FullHeight else CutoutMode.Auto,
        gapDistance = if (isCollapsed) {
            CutoutCardDefaults.GapDistanceCollapsed
        } else {
            CutoutCardDefaults.GapDistanceExpanded
        },
        contentPadding = CutoutCardDefaults.contentPadding(cardPadding),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        if (isCollapsed) {
            ToolbarTitleRow(
                modifier = Modifier.fillMaxWidth(),
                isCollapsed = true,
                filter = filter,
                entryCount = entryCount,
                totalCount = totalCount,
                onClearFilter = onClearFilter,
                onClearAllRequested = onClearAllRequested,
                onOpenSettings = onOpenSettings,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                ToolbarTitleRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = cutoutWidth),
                    isCollapsed = false,
                    filter = filter,
                    entryCount = entryCount,
                    totalCount = totalCount,
                    onClearFilter = onClearFilter,
                    onClearAllRequested = onClearAllRequested,
                    onOpenSettings = onOpenSettings,
                )
                Spacer(modifier = Modifier.height(8.dp))
                HistoryFilterChips(
                    cutoutWidth = cutoutWidth,
                    cutoutHeight = cutoutHeight,
                    filter = filter,
                    onToggleOutcome = onToggleOutcome,
                    onToggleKind = onToggleKind,
                    onClearPathScope = onClearPathScope,
                    onSetPathScope = onSetPathScope,
                )
            }
        }
    }
}

@Composable
private fun ToolbarTitleRow(
    modifier: Modifier = Modifier,
    isCollapsed: Boolean,
    filter: HistoryFilter,
    entryCount: Int,
    totalCount: Int,
    onClearFilter: () -> Unit,
    onClearAllRequested: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.TwoTone.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(if (isCollapsed) 20.dp else 24.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        if (isCollapsed) {
            SummaryRow(
                modifier = Modifier.weight(1f),
                filter = filter,
                entryCount = entryCount,
                totalCount = totalCount,
                onClearFilter = onClearFilter,
            )
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.history_workspace_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                SummaryRow(
                    filter = filter,
                    entryCount = entryCount,
                    totalCount = totalCount,
                    onClearFilter = onClearFilter,
                )
            }
        }
        OverflowMenu(
            onClearAllRequested = onClearAllRequested,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun SummaryRow(
    modifier: Modifier = Modifier,
    filter: HistoryFilter,
    entryCount: Int,
    totalCount: Int,
    onClearFilter: () -> Unit,
) {
    val style = MaterialTheme.typography.bodySmall
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (filter.isUnfiltered) {
            Text(
                text = pluralStringResource(
                    R.plurals.history_summary_total,
                    entryCount,
                    entryCount,
                ),
                style = style,
                color = mutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = pluralStringResource(
                    R.plurals.history_summary_filtered,
                    entryCount,
                    entryCount,
                    totalCount,
                ),
                style = style,
                color = mutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.history_summary_separator),
                style = style,
                color = mutedColor,
            )
            Text(
                text = stringResource(R.string.history_summary_reset_action),
                style = style,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onClearFilter)
                    .padding(vertical = 2.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    onClearAllRequested: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                imageVector = Icons.TwoTone.MoreVert,
                contentDescription = stringResource(R.string.history_toolbar_overflow_action),
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_toolbar_action_clear_all)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.DeleteSweep,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onClearAllRequested()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_toolbar_action_settings)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Settings,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onOpenSettings()
                },
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryToolbarCardExpandedUnfilteredPreview() {
    HistoryToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        filter = HistoryFilter(),
        entryCount = 200,
        totalCount = 200,
        collapsedFraction = 0f,
        onToggleOutcome = {},
        onToggleKind = {},
        onSetPathScope = {},
        onClearPathScope = {},
        onClearFilter = {},
        onClearAllRequested = {},
        onOpenSettings = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryToolbarCardExpandedFilteredPreview() {
    HistoryToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        filter = HistoryFilter(
            outcomes = setOf(HistoryOutcome.FAILED),
            kinds = setOf(Operation.Metadata.Kind.DELETE),
            pathScope = "/storage/emulated/0/DCIM",
        ),
        entryCount = 12,
        totalCount = 200,
        collapsedFraction = 0f,
        onToggleOutcome = {},
        onToggleKind = {},
        onSetPathScope = {},
        onClearPathScope = {},
        onClearFilter = {},
        onClearAllRequested = {},
        onOpenSettings = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryToolbarCardCollapsedPreview() {
    HistoryToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        filter = HistoryFilter(outcomes = setOf(HistoryOutcome.FAILED)),
        entryCount = 12,
        totalCount = 200,
        collapsedFraction = 1f,
        onToggleOutcome = {},
        onToggleKind = {},
        onSetPathScope = {},
        onClearPathScope = {},
        onClearFilter = {},
        onClearAllRequested = {},
        onOpenSettings = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryToolbarCardSplitPanePreview() {
    HistoryToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
        filter = HistoryFilter(),
        entryCount = 42,
        totalCount = 42,
        collapsedFraction = 0f,
        onToggleOutcome = {},
        onToggleKind = {},
        onSetPathScope = {},
        onClearPathScope = {},
        onClearFilter = {},
        onClearAllRequested = {},
        onOpenSettings = {},
    )
}
