package eu.darken.butler.searcher.ui.search.util

import androidx.compose.runtime.Stable
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.resultKey

@Stable
data class SearcherSelectionState(
    val selectableResults: List<SearchItem> = emptyList(),
    val selectedResultIds: Set<String> = emptySet(),
) {
    val isSelectionMode: Boolean = selectedResultIds.isNotEmpty()
    val selectionCount: Int = selectedResultIds.size
    val isAllSelected: Boolean get() = selectableResults.size == selectedResultIds.size && selectableResults.isNotEmpty()

    val selectedResults: List<SearchItem>
        get() = selectableResults.filter { result -> selectedResultIds.contains(result.resultKey) }

    fun isSelected(result: SearchItem): Boolean = selectedResultIds.contains(result.resultKey)

    fun toggleSelection(result: SearchItem): SearcherSelectionState {
        val resultId = result.resultKey
        val newSelectedIds = if (selectedResultIds.contains(resultId)) {
            selectedResultIds - resultId
        } else {
            selectedResultIds + resultId
        }
        return copy(selectedResultIds = newSelectedIds)
    }

    fun selectAll(): SearcherSelectionState {
        return copy(selectedResultIds = selectableResults.map { it.resultKey }.toSet())
    }

    fun addToSelection(items: List<SearchItem>): SearcherSelectionState {
        return copy(selectedResultIds = selectedResultIds + items.map { it.resultKey })
    }

    fun deselectAll(): SearcherSelectionState {
        return copy(selectedResultIds = emptySet())
    }

    fun enterSelectionMode(result: SearchItem): SearcherSelectionState {
        return copy(selectedResultIds = setOf(result.resultKey))
    }
}