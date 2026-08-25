package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.files.APath

/**
 * What an operation set out to do, in terms of paths. Each consumer reads its own field instead of
 * a positional convention over one untyped bag.
 */
data class OperationPathPlan(
    /** What the operation acts ON: sources for copy/move/delete, the created path for create. */
    val targets: List<APath<*>>,
    val destination: Destination? = null,
    /**
     * History scope index + import-sweeper liveness set. Defaults to targets plus the destination;
     * producers whose destination must not become a scope candidate of its own override it.
     */
    val scopePaths: List<APath<*>> = targets + listOfNotNull(destination?.path),
    /** History row label fallback, used only when the operation reported no changes. */
    val representativePath: APath<*>? = targets.firstOrNull() ?: destination?.path,
) {

    /** Every path the operation relates to, for consumers that want the whole set. */
    val allPaths: List<APath<*>> get() = scopePaths

    /**
     * Where the operation puts things. The distinction is what the user asked for, not what the
     * gateway ends up doing: renaming `foo` to `bar` while `bar` exists as a directory is a
     * [RequestedTarget] that the filesystem resolves as a move into that directory.
     */
    sealed interface Destination {
        val path: APath<*>

        /** A directory the items land INSIDE. */
        data class Container(override val path: APath<*>) : Destination

        /** The exact final path the user asked for (rename, move-to-path, archive file). */
        data class RequestedTarget(override val path: APath<*>) : Destination
    }
}
