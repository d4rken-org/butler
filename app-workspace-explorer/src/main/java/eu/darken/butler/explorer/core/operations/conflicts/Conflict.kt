package eu.darken.butler.explorer.core.operations.conflicts

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlin.uuid.Uuid

sealed interface Conflict {
    val conflictId: ConflictId

    interface Resolution

    data class PathAlreadyExists(
        override val conflictId: ConflictId = Uuid.Companion.random(),
        val destination: APathLookup<APath>,
        val source: APathLookup<APath>? = null,
        val canSkip: Boolean = true,
        val canOverwrite: Boolean = true,
        val canRename: Boolean = true,
        val suggestedName: String? = null,
        val canMerge: Boolean = false,
    ) : Conflict {
        sealed interface Resolution : Conflict.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data class Overwrite(val applyToAll: Boolean = false) : Resolution
            data class Rename(val newName: String) : Resolution
            data class Merge(val applyToAll: Boolean = false) : Resolution
            data object Cancel : Resolution
        }
    }

    data class InsufficientPermission(
        override val conflictId: ConflictId = Uuid.Companion.random(),
        val source: APathLookup<APath>,
        val destination: APathLookup<APath>,
        val canSkip: Boolean = true,
    ) : Conflict {
        sealed interface Resolution : Conflict.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data object Cancel : Resolution
        }
    }

    data class InsufficientSpace(
        override val conflictId: ConflictId = Uuid.Companion.random(),
        val source: APathLookup<APath>,
        val destination: APathLookup<APath>,
        val canSkip: Boolean = true,
    ) : Conflict {
        sealed interface Resolution : Conflict.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data object Cancel : Resolution
        }
    }
}