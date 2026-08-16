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

    /** Replaces the selection wholesale; ids of results the current search no longer lists survive. */
    fun setSelection(ids: Set<String>): SearcherSelectionState = copy(selectedResultIds = ids)

    fun selectAll(): SearcherSelectionState {
        return copy(selectedResultIds = selectableResults.map { it.resultKey }.toSet())
    }

    fun addToSelection(items: List<SearchItem>): SearcherSelectionState {
        return copy(selectedResultIds = selectedResultIds + items.map { it.resultKey })
    }

    fun deselectAll(): SearcherSelectionState {
        return copy(selectedResultIds = emptySet())
    }

    /**
     * Long press entry point. Once a selection exists the long press belongs to the drag gesture,
     * so it no longer changes what is selected - taps do that.
     */
    fun enterSelectionMode(result: SearchItem): SearcherSelectionState {
        if (isSelectionMode) return this
        return copy(selectedResultIds = setOf(result.resultKey))
    }
}