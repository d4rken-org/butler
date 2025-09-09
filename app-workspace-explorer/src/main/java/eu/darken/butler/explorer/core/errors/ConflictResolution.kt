package eu.darken.butler.explorer.core.errors

sealed class ConflictResolution {
    data class Skip(val applyToAll: Boolean = false) : ConflictResolution()
    data class Overwrite(val applyToAll: Boolean = false) : ConflictResolution()
    data class Rename(val newName: String) : ConflictResolution()
    object Cancel : ConflictResolution()
}