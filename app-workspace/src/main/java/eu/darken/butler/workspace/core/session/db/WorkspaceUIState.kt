package eu.darken.butler.workspace.core.session.db

import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.serialization.WorkspaceIdSerializer
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Workspace UI state that gets persisted across app restarts.
 * This wrapper allows adding new UI state fields without requiring database migrations.
 */
@Serializable
data class WorkspaceUIState(
    /**
     * Format marker for the stored blob, written on every save.
     *
     * It exists so a row written by a newer build is *recognizable* as such: without it, a blob whose
     * fields this build cannot read is indistinguishable from a corrupted one, and the two want
     * opposite reactions. Reading is unaffected - the converter only logs what it found.
     *
     * Deliberately no write-suppression yet: refusing to overwrite a newer blob means reasoning about
     * the whole save path (partial saves, restore-in-progress, session replacement). The marker is the
     * prerequisite for that decision, not the decision.
     */
    val version: Int = CURRENT_VERSION,
    @Serializable(with = WorkspaceIdSerializer::class)
    val focusedWorkspaceId: Workspace.Id? = null,
    val paneSelections: Map<Int, @Serializable(with = WorkspaceIdSerializer::class) Workspace.Id> = emptyMap(),
    /** Scroll slots per workspace, e.g. Explorer's per-directory list/grid positions. */
    val scrollPositions: Map<@Serializable(with = WorkspaceIdSerializer::class) Workspace.Id, Map<String, WorkspaceScrollPosition>> = emptyMap(),
    /** Floating bar collapse fractions per workspace: bar position (TOP/BOTTOM) -> bar key -> fraction. */
    val barCollapse: Map<@Serializable(with = WorkspaceIdSerializer::class) Workspace.Id, Map<String, Map<String, Float>>> = emptyMap(),
    /**
     * Per-workspace view preferences: slot key -> opaque payload.
     *
     * Opaque at this layer on purpose - the slot keys and their JSON shapes are owned by the writing
     * workspace module, so a payload this build cannot read stays confined to that module's decode.
     */
    val viewPrefs: Map<@Serializable(with = WorkspaceIdSerializer::class) Workspace.Id, Map<String, JsonElement>> = emptyMap(),
) {
    companion object {
        /** Bump only when a field changes meaning; adding a field with a default is not a new version. */
        const val CURRENT_VERSION = 1
    }
}
