package eu.darken.butler.setup.core

data class SetupItem(
    val type: SetupModule.Type,
    val state: SetupModule.State,
    val isRequired: Boolean,
    val priority: Int,
)