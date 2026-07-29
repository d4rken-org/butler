package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.items.ExplorerItemRenderer
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload

/**
 * The grid-style main content of the Explorer page.
 * Moves the whole LazyVerticalGrid so span/maxLineSpan DSL semantics stay intact.
 */
@Composable
internal fun ExplorerGridContent(
    modifier: Modifier = Modifier,
    state: ExplorerWorkspaceViewModel.State,
    vm: ExplorerWorkspaceViewModel?,
    contentFocusedItem: ExplorerItem?,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    dragPayloadFactory: ((ExplorerItem) -> WorkspaceDragPayload?)? = null,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = contentPadding,
    ) {
        if (state.items == null) {
            if (state.error == null) {
                items(12, key = { "skeleton-grid-$it" }) {
                    SkeletonGridItem()
                }
            }
        } else if (state.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                // When favorites are visible below, don't fill the viewport.
                val emptyModifier = if (state.showHomeFavoritesSection) {
                    Modifier.fillMaxSize().padding(vertical = 48.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                Box(
                    modifier = emptyModifier,
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isFilteredEmpty) {
                        EmptyFilteredState(
                            onResetFilters = { vm?.resetFilters() },
                        )
                    } else {
                        EmptyDirectoryState()
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
            item(
                span = { GridItemSpan(maxLineSpan) },
                key = "favorites:divider",
            ) {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerGridContentPreview() {
    PreviewWrapper {
        ExplorerGridContent(
            state = MockDataProvider.createReadyState().copy(viewStyle = ExplorerViewStyle.Grid()),
            vm = null,
            contentFocusedItem = null,
            gridState = rememberLazyGridState(),
            contentPadding = PaddingValues(2.dp),
        )
    }
}
