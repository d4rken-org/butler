package eu.darken.butler.workspace.core

import kotlinx.coroutines.flow.Flow

interface WorkspaceRemote {

    val status: Flow<Status>

    data class Status(
        val count: Int,
        val isButtonActionsFlipped: Boolean,
    )

    suspend fun execute(action: WorkspaceAction)
}