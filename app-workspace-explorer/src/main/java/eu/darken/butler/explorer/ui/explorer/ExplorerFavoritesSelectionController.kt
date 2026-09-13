package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.extensions.matches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Selection state for the Home screen's favorites section.
 *
 * Favorites are deliberately not [eu.darken.butler.explorer.core.engine.ExplorerItem]s, so they
 * cannot enter [eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState] and get their
 * own selection instead. The two never coexist: Home only lists shortcuts, which are not
 * selectable, so no precedence rule is needed anywhere.
 */
class ExplorerFavoritesSelectionController(
    favoritePaths: Flow<List<APath<*>>>,
    scope: CoroutineScope,
    private val tag: String,
) {

    private val selectionFlow = MutableStateFlow<Set<APath<*>>>(emptySet())
    val selection: StateFlow<Set<APath<*>>> = selectionFlow

    init {
        favoritePaths
            .onEach { paths -> pruneAgainst(paths) }
            .launchIn(scope)
    }

    fun toggle(path: APath<*>) {
        selectionFlow.update { current ->
            val selected = current.firstOrNull { it.matches(path) }
            if (selected != null) current - selected else current + path
        }
        log(tag) { "toggleFavoriteSelection(${path.path}): ${selectionFlow.value.size} selected" }
    }

    fun clear() {
        if (selectionFlow.value.isEmpty()) return
        log(tag) { "clearFavoriteSelection()" }
        selectionFlow.value = emptySet()
    }

    /** A favorite that is removed elsewhere — another tab, an expiring undo — must not stay selected. */
    private fun pruneAgainst(paths: List<APath<*>>) {
        selectionFlow.update { current ->
            if (current.isEmpty()) return@update current
            current.filterTo(mutableSetOf()) { selected -> paths.any { it.matches(selected) } }
        }
    }
}
