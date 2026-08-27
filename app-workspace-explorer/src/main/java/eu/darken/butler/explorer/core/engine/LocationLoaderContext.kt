package eu.darken.butler.explorer.core.engine

import androidx.annotation.StringRes
import eu.darken.butler.common.ca.toCaString
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Shared context for location loaders that manages incremental state updates and emissions.
 * Provides a consistent pattern for multi-stage loading with progress tracking.
 *
 * @param T The specific ExplorerLocation type being loaded
 * @param initialState The initial location state with progress
 * @param emit Function to emit state updates to the flow collector
 */
internal class LocationLoaderContext<T : ExplorerLocation>(
    initialState: T,
    private val emit: suspend (T) -> Unit,
) {
    private var currentState = initialState

    val state: T get() = currentState

    suspend fun updateState(transform: T.() -> T) {
        // Check for cancellation before updating state to ensure timely cancellation
        coroutineContext.ensureActive()
        currentState = currentState.transform()
        emit(currentState)
    }

    suspend fun emitState() {
        emit(currentState)
    }
}

/**
 * Updates the secondary progress message for the current loading stage.
 * Handles nullable progress safely across all ExplorerLocation types.
 */
internal suspend fun <T : ExplorerLocation> LocationLoaderContext<T>.updateProgressMsg(
    @StringRes msg: Int
) = updateState {
    @Suppress("UNCHECKED_CAST")
    when (this) {
        is ExplorerLocation.Device -> copy(
            progress = progress?.copy(secondary = msg.toCaString())
        ) as T
        is ExplorerLocation.Network -> copy(
            progress = progress?.copy(secondary = msg.toCaString())
        ) as T
        is ExplorerLocation.Directory -> copy(
            progress = progress?.copy(secondary = msg.toCaString())
        ) as T
        is ExplorerLocation.Home -> copy(
            progress = progress?.copy(secondary = msg.toCaString())
        ) as T
        is ExplorerLocation.Trash.Root -> copy(
            progress = progress?.copy(secondary = msg.toCaString())
        ) as T
        is ExplorerLocation.Trash.Nested -> copy(
            progress = progress?.copy(secondary = msg.toCaString())
        ) as T
    }
}
