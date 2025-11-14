package eu.darken.butler.workspace.core.setup

import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.SetupStateProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


fun SetupStateProvider.module(type: SetupModule.Type): Flow<SetupModule.State?> = state.map { it.modules[type] }