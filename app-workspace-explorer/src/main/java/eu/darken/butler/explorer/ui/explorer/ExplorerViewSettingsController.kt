package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.core.PatternMatcher
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.common.files.metadata.FileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * View style, sort settings and name/type filtering for the item listing.
 * Owns the reactive inputs of the ViewModel's processed-items pipeline.
 */
class ExplorerViewSettingsController(
    private val explorerSettings: ExplorerSettings,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
) {

    private val viewStyleFlow = MutableStateFlow<ExplorerViewStyle>(explorerSettings.defaultViewStyle.valueBlocking)
    val viewStyle: StateFlow<ExplorerViewStyle> = viewStyleFlow

    private val sortSettingsFlow = MutableStateFlow(explorerSettings.sortSettings.valueBlocking)
    val sortSettings: StateFlow<SortSettings> = sortSettingsFlow

    private val filterStateFlow = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = filterStateFlow

    fun updateViewStyle(style: ExplorerViewStyle) {
        viewStyleFlow.value = style
        doLaunch {
            explorerSettings.defaultViewStyle.value(style)
        }
    }

    suspend fun applySortSettings(settings: SortSettings) {
        explorerSettings.sortSettings.value(settings)
        sortSettingsFlow.value = settings
    }

    fun applyFilterState(state: FilterState) {
        filterStateFlow.value = state
    }

    fun resetFilters() {
        filterStateFlow.value = FilterState()
    }

    fun applyFilters(
        items: List<ExplorerItem>,
        filterState: FilterState,
        useRegexPatterns: Boolean,
    ): List<ExplorerItem> {
        return items.filter { item ->
            val itemName = when (item) {
                is ExplorerItem.Path -> item.path.name
                is ExplorerItem.Trash.Root -> item.originalLookup.name
                is ExplorerItem.Trash.Nested -> item.lookup.name
                else -> return@filter true // Keep non-path items (like peek items)
            }

            // Apply exclude pattern first
            if (filterState.excludePattern.isNotBlank()) {
                val excludeRegex = PatternMatcher.toRegexPattern(filterState.excludePattern, useRegexPatterns)
                if (PatternMatcher.matches(itemName, excludeRegex)) {
                    return@filter false
                }
            }

            // Apply include pattern
            if (filterState.includePattern.isNotBlank()) {
                val includeRegex = PatternMatcher.toRegexPattern(filterState.includePattern, useRegexPatterns)
                if (!PatternMatcher.matches(itemName, includeRegex)) {
                    return@filter false
                }
            }

            // Apply file type filter
            when (filterState.fileTypeFilter) {
                FileTypeFilter.FILES_ONLY -> {
                    if (item is ExplorerItem.Directory) return@filter false
                    if (item is ExplorerItem.Trash.Root && item.originalLookup.fileType == FileType.DIRECTORY) return@filter false
                    if (item is ExplorerItem.Trash.Nested && item.isDirectory) return@filter false
                }
                FileTypeFilter.FOLDERS_ONLY -> {
                    if (item is ExplorerItem.File) return@filter false
                    if (item is ExplorerItem.Trash.Root && item.originalLookup.fileType == FileType.FILE) return@filter false
                    if (item is ExplorerItem.Trash.Nested && item.isFile) return@filter false
                }
                FileTypeFilter.ALL -> {} // No filtering needed
            }

            true
        }
    }
}
