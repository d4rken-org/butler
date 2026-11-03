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
     * Where the operation puts things, as the user asked for it: pasting into `bar` is a [Container]
     * (`bar/foo`), renaming `foo` to `bar` is a [RequestedTarget] (`bar` itself, even if a directory
     * `bar` already exists).
     */
    sealed interface Destination {
        val path: APath<*>

        /** A directory the items land INSIDE. */
        data class Container(override val path: APath<*>) : Destination

        /** The exact final path the user asked for (rename, move-to-path, archive file). */
        data class RequestedTarget(override val path: APath<*>) : Destination
    }
}
