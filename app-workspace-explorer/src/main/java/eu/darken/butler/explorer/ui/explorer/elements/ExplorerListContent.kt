package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.dragselect.listDragSelect
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.dragselect.explorerDragSelectClaims
import eu.darken.butler.explorer.ui.explorer.dragselect.explorerDragSelectItems
import eu.darken.butler.explorer.ui.explorer.dragselect.explorerDragSelectKeys
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.items.ExplorerItemRenderer
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.ui.common.WorkspacePaddings

/**
 * The list-style main content of the Explorer page.
 * Moves the whole LazyColumn so item/span DSL semantics stay intact.
 */
@Composable
internal fun ExplorerListContent(
    modifier: Modifier = Modifier,
    state: ExplorerWorkspaceViewModel.State,
    vm: ExplorerWorkspaceViewModel?,
    contentFocusedItem: ExplorerItem?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    dragPayloadFactory: ((ExplorerItem) -> WorkspaceDragPayload?)? = null,
) {
    // Skeletons must never attach to the persisted scroll state: restore readiness would fire
    // against placeholder content and the recorder would persist the resulting clamp.
    val skeletonListState = rememberLazyListState()
    val effectiveListState = if (state.items == null) skeletonListState else listState
    LazyColumn(
        state = effectiveListState,
        modifier = modifier.listDragSelect(
            state = effectiveListState,
            orderedKeys = { explorerDragSelectKeys(state) },
            currentSelection = { state.selectionState.selectedItems.mapTo(mutableSetOf()) { it.id } },
            onSelectionChange = { ids -> vm?.setSelection(explorerDragSelectItems(state, ids)) },
            enabled = { id -> explorerDragSelectClaims(state, id, dragPayloadFactory) },
        ),
        verticalArrangement = Arrangement.spacedBy(WorkspacePaddings.ListGapDense),
        contentPadding = contentPadding,
    ) {
        if (state.items == null) {
            when {
                // Fills the content area instead of floating over it: there is no content left to
                // look at, and the state carries the way out of the location.
                state.error is PathNotFoundException -> item(key = "notfound") {
                    PathNotFoundState(
                        modifier = Modifier.fillParentMaxSize(),
                        onRetry = { vm?.retryNavigation() },
                        onLeave = { vm?.dismissNavigationError() },
                    )
                }

                state.error == null -> items(10, key = { "skeleton-$it" }) {
                    SkeletonListItem()
                }
            }
        } else if (state.items.isEmpty()) {
            item(key = "empty") {
                // When favorites are visible below, don't fill the viewport
                // — otherwise the empty-state pushes favorites below the fold.
                val emptyModifier = if (state.showHomeFavoritesSection) {
                    Modifier.fillMaxSize().padding(vertical = 48.dp)
                } else {
                    Modifier.fillParentMaxSize()
                }
                Box(
                    modifier = emptyModifier,
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        state.isFilteredEmpty -> EmptyFilteredState(
                            onResetFilters = { vm?.resetFilters() },
                        )

                        state.currentLocation is ExplorerLocation.Network -> EmptyNetworkState(
                            onAddLocation = {
                                vm?.executeAction(ExplorerActionBarItem.Network.AddLocation())
                            },
                            showAddAction = state.networkManagementEnabled,
                        )

                        else -> EmptyDirectoryState()
                    }
                }
            }
        } else {
            items(
                items = state.items,
                key = { it.id },
                contentType = ExplorerItem::contentType,
            ) { item ->
                ExplorerItemRenderer(
                    item = item,
                    viewStyle = state.viewStyle,
                    state = state,
                    isFocused = item == contentFocusedItem,
                    onItemClick = { vm?.onItemClick(it) },
                    onItemLongClick = { vm?.onItemLongClick(it) },
                    onNavigate = { vm?.navigate(it) },
                    onToggleSelection = { vm?.toggleItemSelection(it) },
                    dragPayloadFactory = dragPayloadFactory,
                )
            }
        }
        // Trailing block layout is mirrored by State.favoriteContentIndex() for scroll-to-favorite.
        if (state.showHomeFavoritesSection) {
            item(key = "favorites:divider") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            favoritesSection(
                favorites = state.favorites,
                highlightedItemIds = state.highlightedItemIds,
                onClick = { vm?.onFavoriteClick(it) },
                onRemove = { vm?.onFavoriteRemove(it) },
            )
        }
    }
}

internal fun ExplorerItem.contentType(): String = when (this) {
    is ExplorerItem.Lookup -> "lookup"
    is ExplorerItem.Peek -> "peek"
    is ExplorerItem.Shortcut -> "shortcut"
    is ExplorerItem.Storage -> "storage"
    is ExplorerItem.Trash.Root -> "trashRoot"
    is ExplorerItem.Trash.Nested -> "trashNested"
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerListContentPreview() {
    PreviewWrapper {
        ExplorerListContent(
            state = MockDataProvider.createReadyState(),
            vm = null,
            contentFocusedItem = null,
            listState = rememberLazyListState(),
            contentPadding = PaddingValues(WorkspacePaddings.ContentHorizontal),
        )
    }
}
