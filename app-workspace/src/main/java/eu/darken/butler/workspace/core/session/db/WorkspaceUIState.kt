package eu.darken.butler.workspace.core.session.db

import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.serialization.WorkspaceIdSerializer
import kotlinx.serialization.Serializable

/**
 * Workspace UI state that gets persisted across app restarts.
 * This wrapper allows adding new UI state fields without requiring database migrations.
 */
@Serializable
data class WorkspaceUIState(
    @Serializable(with = WorkspaceIdSerializer::class)
    val focusedWorkspaceId: Workspace.Id? = null,
    val paneSelections: Map<Int, @Serializable(with = WorkspaceIdSerializer::class) Workspace.Id> = emptyMap(),
)
