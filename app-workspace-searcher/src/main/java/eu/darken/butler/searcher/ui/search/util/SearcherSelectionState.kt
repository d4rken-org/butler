package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.searcher.core.SearchItem

data class SearcherSelectionState(
    val selectableResults: List<SearchItem> = emptyList(),
    val selectedResultIds: Set<String> = emptySet(),
) {
    val isSelectionMode: Boolean = selectedResultIds.isNotEmpty()
    val selectionCount: Int = selectedResultIds.size
    val isAllSelected: Boolean get() = selectableResults.size == selectedResultIds.size && selectableResults.isNotEmpty()

    val selectedResults: List<SearchItem>
        get() = selectableResults.filter { result -> selectedResultIds.contains(result.path.path) }

    fun isSelected(result: SearchItem): Boolean = selectedResultIds.contains(result.path.path)

    fun toggleSelection(result: SearchItem): SearcherSelectionState {
        val resultId = result.path.path
        val newSelectedIds = if (selectedResultIds.contains(resultId)) {
            selectedResultIds - resultId
        } else {
            selectedResultIds + resultId
        }
        return copy(selectedResultIds = newSelectedIds)
    }

    fun selectAll(): SearcherSelectionState {
        return copy(selectedResultIds = selectableResults.map { it.path.path }.toSet())
    }

    fun deselectAll(): SearcherSelectionState {
        return copy(selectedResultIds = emptySet())
    }

    fun enterSelectionMode(result: SearchItem): SearcherSelectionState {
        return copy(selectedResultIds = setOf(result.path.path))
    }
}