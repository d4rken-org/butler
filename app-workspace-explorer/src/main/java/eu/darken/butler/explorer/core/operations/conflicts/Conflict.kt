package eu.darken.butler.explorer.core.operations.conflicts

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.operations.ConflictType
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class ConflictInfo(
    val conflictId: ConflictId = Uuid.Companion.random(),
    val type: ConflictType,
    val sourcePath: APath,
    val targetPath: APath? = null,
    val sourceSize: Long? = null,
    val targetSize: Long? = null,
    val sourceModified: Instant? = null,
    val targetModified: Instant? = null,
    val message: String? = null,
    val canSkip: Boolean = true,
    val canOverwrite: Boolean = true,
    val canMerge: Boolean = false,
    val canRename: Boolean = true,
    val suggestedName: String? = null,
)