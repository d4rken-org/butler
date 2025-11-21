package eu.darken.butler.workspace.core.session.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceSessionDao {

    @Query("SELECT * FROM workspace_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): WorkspaceSessionEntity?

    @Upsert
    suspend fun upsertSession(session: WorkspaceSessionEntity)

    @Query("DELETE FROM workspace_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT * FROM workspace_instances WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    suspend fun getWorkspaces(sessionId: String): List<WorkspaceInstanceEntity>

    @Query("SELECT workspaceId FROM workspace_instances WHERE sessionId = :sessionId")
    suspend fun getWorkspaceIds(sessionId: String): List<Workspace.Id>

    @Query("SELECT COUNT(*) FROM workspace_instances WHERE sessionId = :sessionId")
    fun getWorkspaceCountFlow(sessionId: String): Flow<Int>

    @Query("SELECT * FROM workspace_instances WHERE workspaceId = :id")
    suspend fun getWorkspaceById(id: Workspace.Id): WorkspaceInstanceEntity?

    @Upsert
    suspend fun upsertWorkspace(workspace: WorkspaceInstanceEntity)

    @Query("DELETE FROM workspace_instances WHERE workspaceId IN (:ids)")
    suspend fun deleteWorkspacesByIds(ids: List<Workspace.Id>)

    @Query("DELETE FROM workspace_instances WHERE sessionId = :sessionId")
    suspend fun deleteAllWorkspaces(sessionId: String)

    @Transaction
    suspend fun clearAllSessionData(sessionId: String) {
        deleteSession(sessionId)
        deleteAllWorkspaces(sessionId)
    }
}
