package eu.darken.butler.workspace.core.session.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import eu.darken.butler.workspace.core.Workspace
import kotlin.time.Instant

/**
 * Represents a single workspace instance within a session
 */
@Entity(
    tableName = "workspace_instances",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["workspaceId"])
    ]
)
data class WorkspaceInstanceEntity(
    @PrimaryKey val workspaceId: Workspace.Id,
    val sessionId: String,
    val type: Workspace.Type,
    val orderIndex: Int,
    val lastModified: Instant,
    val arguments: String,
)
