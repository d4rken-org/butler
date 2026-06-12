package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.common.CutoutAwareColumn
import eu.darken.butler.workspace.ui.common.CutoutCard
import eu.darken.butler.workspace.ui.common.CutoutCardDefaults
import eu.darken.butler.workspace.ui.common.CutoutMode
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun AppsToolbarCard(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    searchQuery: TextFieldValue,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    filterConfig: TagFilterConfig,
    onFilterAdd: () -> Unit,
    onFilterRemove: (AppTag, isExcluded: Boolean) -> Unit,
    design: WorkspaceDesign,
    collapsedFraction: Float = 0f,
) {
    val isCollapsed = collapsedFraction > 0.5f
    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) CutoutCardDefaults.ContentPaddingCollapsed else CutoutCardDefaults.ContentPaddingExpanded,
        label = "cardPadding",
    )
    val filterCount = filterConfig.includeTags.size + filterConfig.excludeTags.size

    CutoutCard(
        modifier = modifier.fillMaxWidth(),
        cutoutContent = if (design.isSingle) {
            {
                WorkspaceButton(
                    currentWorkspaceId = workspaceId,
                    buttonSize = if (isCollapsed) WorkspaceButtonDefaults.sizeCompact else WorkspaceButtonDefaults.sizeDefault,
                )
            }
        } else null,
        cutoutMode = if (isCollapsed) CutoutMode.FullHeight else CutoutMode.Corner,
        gapDistance = if (isCollapsed) CutoutCardDefaults.GapDistanceCollapsed else CutoutCardDefaults.GapDistanceExpanded,
        contentPadding = CutoutCardDefaults.contentPadding(cardPadding),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (isCollapsed) {
            // Collapsed state - compact display with filter count badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = Icons.TwoTone.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = searchQuery.text.ifBlank { stringResource(R.string.apps_search_hint) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (searchQuery.text.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (filterCount > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.apps_filter_count, filterCount, filterCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            // Expanded state - full search bar + filter chips
            CutoutAwareColumn(
                cutoutWidth = cutoutWidth,
                cutoutHeight = cutoutHeight,
            ) {
                Column(modifier = Modifier.defaultMinSize(minHeight = cutoutHeight)) {
                    AppsSearchBar(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                AppsFilterChipBar(
                    modifier = if (cutoutHeight == 0.dp) Modifier.padding(top = 8.dp) else Modifier,
                    filterConfig = filterConfig,
                    onTagRemove = onFilterRemove,
                    onAddClick = onFilterAdd,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsToolbarCardPreview() {
    AppsToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        searchQuery = TextFieldValue(""),
        onSearchQueryChange = {},
        filterConfig = TagFilterConfig(),
        onFilterAdd = {},
        onFilterRemove = { _, _ -> },
        design = WorkspaceDesign(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsToolbarCardWithQueryPreview() {
    AppsToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        searchQuery = TextFieldValue("Chrome"),
        onSearchQueryChange = {},
        filterConfig = TagFilterConfig(),
        onFilterAdd = {},
        onFilterRemove = { _, _ -> },
        design = WorkspaceDesign(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsToolbarCardWithFiltersPreview() {
    AppsToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        searchQuery = TextFieldValue(""),
        onSearchQueryChange = {},
        filterConfig = TagFilterConfig(
            includeTags = setOf(AppTag.UserApp, AppTag.Enabled),
            excludeTags = setOf(AppTag.Disabled),
        ),
        onFilterAdd = {},
        onFilterRemove = { _, _ -> },
        design = WorkspaceDesign(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsToolbarCardCollapsedPreview() {
    AppsToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        searchQuery = TextFieldValue("Chrome"),
        onSearchQueryChange = {},
        filterConfig = TagFilterConfig(),
        onFilterAdd = {},
        onFilterRemove = { _, _ -> },
        design = WorkspaceDesign(),
        collapsedFraction = 1f,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsToolbarCardCollapsedWithFiltersPreview() {
    AppsToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        searchQuery = TextFieldValue("Chrome"),
        onSearchQueryChange = {},
        filterConfig = TagFilterConfig(
            includeTags = setOf(AppTag.UserApp, AppTag.Enabled),
            excludeTags = setOf(AppTag.System),
        ),
        onFilterAdd = {},
        onFilterRemove = { _, _ -> },
        design = WorkspaceDesign(),
        collapsedFraction = 1f,
    )
}
