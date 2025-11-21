package eu.darken.butler.workspace.core

import kotlinx.coroutines.flow.Flow

interface WorkspaceProvider {
    fun retrieve(id: Workspace.Id): Flow<@JvmSuppressWildcards Workspace<out Workspace.Arguments>?>
}