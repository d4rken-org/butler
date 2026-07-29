package eu.darken.butler.workspace.ui.dnd

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.extensions.LocalPathNormalization
import eu.darken.butler.common.files.extensions.isDescendantOf
import eu.darken.butler.common.files.extensions.isParentOf
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload

/**
 * Rejects drops that would be a no-op or destructive: dropping an item onto itself, back into the
 * folder it already lives in, or a folder into its own subtree.
 *
 * Both sides are normalized first — a searcher result spelled `/sdcard/x` and an explorer location
 * spelled `/storage/emulated/0/x` are the same folder and must not slip past these checks.
 */
fun validateDropPaths(items: List<WorkspaceDragPayload.Item>, destination: APath<*>): Boolean {
    if (items.isEmpty()) return false
    val target = LocalPathNormalization.comparable(destination)
    return items.none { item ->
        val source = LocalPathNormalization.comparable(item.path)
        target.matches(source) ||
            target.isParentOf(source) ||
            (item.kind == WorkspaceDragPayload.Kind.DIRECTORY && target.isDescendantOf(source))
    }
}
