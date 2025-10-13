package eu.darken.butler.searcher.ui.search

import eu.darken.butler.searcher.core.SearchResult

data class SearcherSelectionState(
    val selectableResults: List<SearchResult> = emptyList(),
    val selectedResultIds: Set<String> = emptySet(),
) {
    val isSelectionMode: Boolean = selectedResultIds.isNotEmpty()
    val selectionCount: Int = selectedResultIds.size
    val isAllSelected: Boolean get() = selectableResults.size == selectedResultIds.size && selectableResults.isNotEmpty()

    val selectedResults: List<SearchResult>
        get() = selectableResults.filter { result -> selectedResultIds.contains(result.path.path) }

    fun isSelected(result: SearchResult): Boolean = selectedResultIds.contains(result.path.path)

    fun toggleSelection(result: SearchResult): SearcherSelectionState {
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

    fun enterSelectionMode(result: SearchResult): SearcherSelectionState {
        return copy(selectedResultIds = setOf(result.path.path))
    }
}