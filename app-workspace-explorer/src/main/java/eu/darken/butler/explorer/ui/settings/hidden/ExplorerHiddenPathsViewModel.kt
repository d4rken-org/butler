package eu.darken.butler.explorer.ui.settings.hidden

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.hidden.ExplorerHiddenPathsRepo
import eu.darken.butler.explorer.core.hidden.HiddenEntry
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class ExplorerHiddenPathsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val hiddenPaths: ExplorerHiddenPathsRepo,
) : ViewModel4(dispatcherProvider, logTag("Explorer", "Settings", "HiddenPaths", "VM")) {

    val state = hiddenPaths.state
        .filterNotNull()
        .map { State(entries = it.entries) }
        .asStateFlow()

    fun remove(entry: HiddenEntry) = launch {
        log(tag) { "remove(${entry.key})" }
        hiddenPaths.unhideKeys(setOf(entry.key))
    }

    data class State(
        val entries: List<HiddenEntry>,
    )
}
