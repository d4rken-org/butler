package eu.darken.butler.workspace.core.session

import eu.darken.butler.workspace.core.Workspace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a saved workspace session that can be restored on app startup
 */
@Serializable
data class WorkspaceSession(
    val version: Int = 1,
    val timestamp: String, // Will be Instant when serialization is ready
    val workspaces: List<WorkspaceSessionData>,
)

/**
 * Data for a single workspace in a session
 */
@Serializable
data class WorkspaceSessionData(
    val id: String,
    val type: Workspace.Type,
    val arguments: JsonElement? = null,
    val customState: JsonElement? = null,
    val order: Int = 0,
)

/**
 * Interface for workspaces to implement their own session persistence
 */
interface WorkspaceSerializable {
    /**
     * Serialize workspace-specific state to JSON
     */
    fun serializeState(): JsonElement?

    /**
     * Deserialize and restore workspace-specific state from JSON
     */
    suspend fun restoreState(state: JsonElement)
}

/**
 * Restoration result for a single workspace
 */
sealed class WorkspaceRestorationResult {
    data class Success(val workspaceId: Workspace.Id) : WorkspaceRestorationResult()
    data class PartialSuccess(val workspaceId: Workspace.Id, val warning: String) : WorkspaceRestorationResult()
    data class Failed(val type: Workspace.Type, val error: String) : WorkspaceRestorationResult()
}