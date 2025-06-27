package eu.darken.butler.workspace.core

import kotlinx.coroutines.flow.Flow

interface WorkspaceProvider {
    suspend fun get(id: Workspace.Id): Flow<Workspace?>
}