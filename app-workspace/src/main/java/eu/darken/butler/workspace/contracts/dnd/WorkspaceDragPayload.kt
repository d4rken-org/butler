package eu.darken.butler.workspace.contracts.dnd

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace

/**
 * What a cross-workspace drag carries. Travels in-process as the drag session's local state, the
 * ClipData only holds a marker so nothing leaks to other apps.
 */
data class WorkspaceDragPayload(
    val sourceWorkspaceId: Workspace.Id,
    val items: List<Item>,
    /** False when the source can't give the items up (archive contents, read-only location). */
    val allowMove: Boolean,
) {

    data class Item(
        val path: APath<*>,
        val kind: Kind,
    )

    enum class Kind { DIRECTORY, FILE_TEXT, FILE_OTHER }

    companion object {
        const val CLIP_LABEL = "butler/workspace-drag"
    }
}
