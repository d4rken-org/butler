package eu.darken.butler.workspace.core.session.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * Represents the metadata for a workspace session
 */
@Entity(tableName = "workspace_sessions")
data class WorkspaceSessionEntity(
    @PrimaryKey val sessionId: String,
    val version: Int = 1,
    val label: String,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val uiState: WorkspaceUIState = WorkspaceUIState(),
)
