package eu.darken.butler.setup.core

import kotlinx.coroutines.flow.Flow

/**
 * Provides access to setup module states from the app layer to workspace modules.
 * This abstraction allows workspace modules to observe setup states without
 * depending on the app module directly.
 */
interface SetupStateProvider {

    data class State(
        val modules: Map<SetupModule.Type, SetupModule.State>
    )

    val state: Flow<State>
}