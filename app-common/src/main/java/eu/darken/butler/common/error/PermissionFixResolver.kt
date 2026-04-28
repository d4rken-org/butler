package eu.darken.butler.common.error

/**
 * Resolves the most useful [Fix] suggestion for a permission-style error at render time.
 *
 * The throw site only reports what failed structurally; the resolver inspects current state
 * (Root/Shizuku availability, granted permissions, …) and decides which Setup screen — if any —
 * would actually help the user. Returning null means no fix can be suggested right now.
 */
fun interface PermissionFixResolver {
    fun resolve(error: Throwable): Fix?
}
