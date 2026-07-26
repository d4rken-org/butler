package eu.darken.butler.workspace.core.usage

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Usage counter for a single workspace type.
 *
 * [type] holds the enum **name**, not the enum itself: a renamed or removed
 * `Workspace.Type` must degrade to "drop that entry" instead of failing to deserialize the
 * whole persisted blob.
 */
@Serializable
data class WorkspaceTypeUsage(
    val type: String,
    val useCount: Int = 1,
    @Contextual val lastUsed: Instant,
)

@Serializable
data class WorkspaceUsageData(
    val entries: List<WorkspaceTypeUsage> = emptyList(),
)
