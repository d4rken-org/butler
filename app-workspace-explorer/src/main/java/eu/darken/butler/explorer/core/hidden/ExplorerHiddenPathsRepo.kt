package eu.darken.butler.explorer.core.hidden

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.sorting.rules.sortPathKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The paths the user hid from Explorer listings, persisted via [ExplorerSettings.hiddenEntries].
 *
 * Mutations run inside the DataStore update lambda, so they act on committed storage rather than
 * on the [state] snapshot.
 */
@Singleton
class ExplorerHiddenPathsRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val settings: ExplorerSettings,
) {

    /** Null until the stored list has been read once; a listing must not be published before that. */
    val state: StateFlow<HiddenPaths?> = settings.hiddenEntries.flow
        .map { HiddenPaths(it) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    fun isHidden(path: APath<*>): Boolean = state.value?.isHidden(path) == true

    /** @return the paths that were actually added; paths already hidden are skipped. */
    suspend fun hideAll(paths: List<APath<*>>): List<APath<*>> {
        var added = emptyList<APath<*>>()
        settings.hiddenEntries.update { current ->
            val known = current.mapTo(mutableSetOf()) { it.key }
            val newEntries = paths.mapNotNull { path ->
                val key = path.sortPathKey()
                if (known.add(key)) HiddenEntry(key = key, path = path) else null
            }
            // Assigned on every invocation: the transform may run more than once.
            added = newEntries.map { it.path }
            current + newEntries
        }
        log(TAG, INFO) { "Hid ${added.size} path(s)." }
        return added
    }

    suspend fun unhideAll(paths: List<APath<*>>) {
        unhideKeys(paths.mapTo(mutableSetOf()) { it.sortPathKey() })
    }

    suspend fun unhideKeys(keys: Set<String>) {
        val result = settings.hiddenEntries.update { current -> current.filterNot { it.key in keys } }
        log(TAG, INFO) { "Unhid ${result.old.size - result.new.size} path(s)." }
    }

    companion object {
        private val TAG = logTag("Explorer", "HiddenPathsRepo")
    }
}
