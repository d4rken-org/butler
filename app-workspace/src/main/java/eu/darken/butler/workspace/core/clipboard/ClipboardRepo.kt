package eu.darken.butler.workspace.core.clipboard

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
class ClipboardRepo @Inject constructor() {

    private val lock = Mutex()
    private val _state = MutableStateFlow(State())
    val state: Flow<State> = _state

    suspend fun add(clip: ClipboardClip) = lock.withLock {
        log(TAG, INFO) { "Adding entry $clip" }
        _state.value = _state.value.copy(
            entries = (listOf(clip) + _state.value.entries.toMutableList()).take(3)
        )
    }

    suspend fun remove(id: Uuid) = lock.withLock {
        log(TAG, INFO) { "Removing entry with id $id" }
        _state.value = _state.value.copy(
            entries = _state.value.entries.filter { it.id != id }
        )
    }

    suspend fun prune(id: Workspace.Id) = lock.withLock {
        log(TAG, INFO) { "Pruning entries for workspace $id" }
        _state.value = _state.value.copy(
            entries = _state.value.entries.filter { it.origin != id }
        )
    }

    suspend fun clear() = lock.withLock {
        log(TAG, INFO) { "Clearing clipboard" }
        _state.value = State()
    }

    data class State(
        val entries: List<ClipboardClip> = emptyList(),
    )

    companion object {
        private val TAG = logTag("Workspace", "Clipboard", "Repo")
    }
}