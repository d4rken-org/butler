package eu.darken.butler.workspace.core.session

import eu.darken.butler.workspace.core.Workspace
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents a saved workspace session that can be restored on app startup
 */
@Serializable
data class WorkspaceSession(
    val sessionId: Uuid = Uuid.random(),
    @Contextual val createdAt: Instant = Clock.System.now(),
    val workspaces: List<WorkspaceSessionData> = emptyList(),
    val focusedWorkspaceId: String? = null,
    val selectedWorkspaces: Map<Int, String> = emptyMap(),
)

/**
 * Data for a single workspace in a session
 */
@Serializable
data class WorkspaceSessionData(
    val id: String,
    val type: Workspace.Type,
    val arguments: JsonElement,
)