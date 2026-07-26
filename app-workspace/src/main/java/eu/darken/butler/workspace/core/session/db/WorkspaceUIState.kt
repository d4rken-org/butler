package eu.darken.butler.workspace.core.session.db

import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.serialization.WorkspaceIdSerializer
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
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
    /** Scroll slots per workspace, e.g. Explorer's per-directory list/grid positions. */
    val scrollPositions: Map<@Serializable(with = WorkspaceIdSerializer::class) Workspace.Id, Map<String, WorkspaceScrollPosition>> = emptyMap(),
    /** Floating bar collapse fraction per workspace, keyed by bar position (TOP/BOTTOM). */
    val barCollapse: Map<@Serializable(with = WorkspaceIdSerializer::class) Workspace.Id, Map<String, Float>> = emptyMap(),
)
