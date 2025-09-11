package eu.darken.butler.setup.core

import eu.darken.butler.setup.core.SetupModule

data class SetupItem(
    val type: SetupModule.Type,
    val state: SetupModule.State,
    val isRequired: Boolean,
    val priority: Int,
)