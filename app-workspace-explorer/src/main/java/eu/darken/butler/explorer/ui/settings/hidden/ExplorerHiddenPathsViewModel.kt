package eu.darken.butler.explorer.ui.settings.hidden

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.hidden.ExplorerHiddenPathsRepo
import eu.darken.butler.explorer.core.hidden.HiddenEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import javax.inject.Inject

@HiltViewModel
class ExplorerHiddenPathsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val hiddenPaths: ExplorerHiddenPathsRepo,
) : ViewModel4(dispatcherProvider, logTag("Explorer", "Settings", "HiddenPaths", "VM")) {

    private val isSearching = MutableStateFlow(false)
    private val query = MutableStateFlow("")
    private val pendingUnhideKeys = MutableStateFlow<Set<String>?>(null)

    val state = combine(
        hiddenPaths.state.filterNotNull(),
        isSearching,
        query,
        pendingUnhideKeys,
    ) { hidden, isSearching, query, pendingKeys ->
        State(
            entries = hidden.entries,
            visibleEntries = hidden.entries.matching(query),
            query = query,
            isSearching = isSearching,
            confirmUnhideCount = pendingKeys?.size,
        )
    }.asStateFlow()

    fun remove(entry: HiddenEntry) = launch {
        log(tag) { "remove(${entry.key})" }
        hiddenPaths.unhideKeys(setOf(entry.key))
    }

    fun openSearch() {
        log(tag) { "openSearch()" }
        isSearching.value = true
    }

    fun closeSearch() {
        log(tag) { "closeSearch()" }
        isSearching.value = false
        query.value = ""
    }

    fun setQuery(newQuery: String) {
        query.value = newQuery
    }

    fun requestUnhideAll() {
        val entries = hiddenPaths.state.value?.entries ?: return
        val keys = entries.matching(query.value).mapTo(mutableSetOf()) { it.key }
        log(tag) { "requestUnhideAll(): ${keys.size} entries" }
        if (keys.isEmpty()) return
        pendingUnhideKeys.value = keys
    }

    fun confirmUnhideAll() {
        val keys = pendingUnhideKeys.value ?: return
        pendingUnhideKeys.value = null
        log(tag) { "confirmUnhideAll(): ${keys.size} entries" }
        launch { hiddenPaths.unhideKeys(keys) }
    }

    fun dismissUnhideAll() {
        log(tag) { "dismissUnhideAll()" }
        pendingUnhideKeys.value = null
    }

    data class State(
        val entries: List<HiddenEntry>,
        val visibleEntries: List<HiddenEntry> = entries,
        val query: String = "",
        val isSearching: Boolean = false,
        val confirmUnhideCount: Int? = null,
    )

    companion object {
        internal fun List<HiddenEntry>.matching(query: String): List<HiddenEntry> {
            if (query.isBlank()) return this
            return filter {
                it.path.name.contains(query, ignoreCase = true) || it.path.path.contains(query, ignoreCase = true)
            }
        }
    }
}
