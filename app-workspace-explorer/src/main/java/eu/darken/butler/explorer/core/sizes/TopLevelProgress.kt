package eu.darken.butler.explorer.core.sizes

/**
 * How far a walk of a root has gotten, counted in the root's direct [children].
 *
 * Entries arrive in whatever order the walk produces them - a delegated subtree streams from the
 * host side and can interleave - so a child is only ever added, never removed, and [done] can only
 * grow. While the walk runs the most recently seen child is treated as still in progress and left
 * out of [done]; [finish] folds it back in.
 */
class TopLevelProgress(
    private val rootKey: String,
    private val children: Set<String>,
) {

    private val prefix = if (rootKey == "/") "/" else "$rootKey/"
    private val seen = HashSet<String>()
    private var isFinished = false

    val total: Int = children.size

    val done: Int
        get() = if (isFinished) seen.size.coerceAtMost(total) else (seen.size - 1).coerceAtLeast(0)

    /** Key of the child the walk touched last, null until one was seen. */
    var current: String? = null
        private set

    fun onSeen(path: String) {
        val child = childOf(path) ?: return
        seen.add(child)
        current = child
    }

    fun finish() {
        isFinished = true
    }

    private fun childOf(path: String): String? {
        if (!path.startsWith(prefix)) return null
        val relative = path.substring(prefix.length)
        if (relative.isEmpty()) return null
        val key = prefix + relative.substringBefore('/')
        return key.takeIf { it in children }
    }
}
