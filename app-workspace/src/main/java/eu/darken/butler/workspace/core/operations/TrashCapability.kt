package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath

/**
 * Split of a delete selection by whether the trash can hold the item
 */
data class TrashPartition(
    val trashable: Set<APath<*>>,
    val untrashable: Set<APath<*>>,
)

/**
 * Single source of truth for trash support, shared by [CoreDeleteExecutor] and the delete
 * confirmation dialog so the wording and the actual outcome cannot drift apart.
 */
fun partitionByTrashSupport(targets: Set<APath<*>>): TrashPartition {
    val (trashable, untrashable) = targets.partition { it is LocalPath }
    return TrashPartition(
        trashable = trashable.toSet(),
        untrashable = untrashable.toSet(),
    )
}

/**
 * Whether a delete can go ahead without the confirmation dialog: only a move into the trash, which
 * the user can undo, and only when they turned the confirmation off.
 */
fun skipsDeleteConfirmation(
    targets: Set<APath<*>>,
    permanentRequested: Boolean,
    trashEnabled: Boolean,
    confirmTrashMove: Boolean,
): Boolean = !confirmTrashMove &&
    trashEnabled &&
    !permanentRequested &&
    targets.isNotEmpty() &&
    partitionByTrashSupport(targets).untrashable.isEmpty()
