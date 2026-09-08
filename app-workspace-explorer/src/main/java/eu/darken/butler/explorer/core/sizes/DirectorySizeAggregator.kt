package eu.darken.butler.explorer.core.sizes

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.metadata.FileType
import kotlin.time.Instant

/**
 * Sums a walk of [root] into a per-directory total.
 *
 * Not thread-safe: every entry and every error has to be handed in from the same coroutine.
 */
class DirectorySizeAggregator(
    private val root: APath<*>,
    private val allocationCoverage: AndroidDataSizeEstimator? = null,
) {

    private val rootKey = root.path
    private val sizes = HashMap<String, Long>().apply { put(rootKey, 0L) }
    private val incomplete = HashSet<String>()
    private val problems = ArrayList<ScanProblem>()

    var itemCount: Long = 0
        private set
    var errorCount: Int = 0
        private set

    fun onEntry(lookup: APathLookup<*>) {
        allocationCoverage?.onEntry(lookup)
        itemCount++
        val key = lookup.path
        if (!isUnderRoot(key)) return

        when (lookup.fileType) {
            FileType.DIRECTORY -> {
                sizes.putIfAbsent(key, 0L)
                if (lookup.error != null) markIncomplete(key)
            }

            FileType.FILE, FileType.SYMBOLIC_LINK -> {
                val size = lookup.size
                if (size == null) {
                    recordProblem(lookup, lookup.error)
                    markIncomplete(parentKey(key))
                } else {
                    forEachAncestorDir(key) { sizes[it] = (sizes[it] ?: 0L) + size }
                }
            }

            FileType.UNKNOWN -> {
                recordProblem(lookup, lookup.error)
                markIncomplete(parentKey(key))
            }
        }
    }

    /**
     * A delegated host-side stream can fail after it has emitted part of a subtree and is then
     * reported against the subtree root only, so everything already recorded below [lookup] holds a
     * truncated total and has to be marked too.
     */
    fun onError(lookup: APathLookup<*>, message: String?) {
        allocationCoverage?.onError(lookup.lookedUp)
        recordProblem(lookup, message)
        val key = lookup.path
        if (key != rootKey && !isUnderRoot(key)) return

        sizes.putIfAbsent(key, 0L)
        markIncomplete(key)

        if (key == rootKey) {
            incomplete.addAll(sizes.keys)
        } else {
            val prefix = "$key/"
            sizes.keys.forEach { if (it.startsWith(prefix)) incomplete.add(it) }
        }
    }

    fun result(scannedAt: Instant) = DirectoryScan(
        root = root,
        scannedAt = scannedAt,
        sizes = sizes.mapValues { (key, bytes) -> DirectorySize(bytes, key !in incomplete) },
        itemCount = itemCount,
        errorCount = errorCount,
        problems = problems.toList(),
    )

    /** Every problem is counted, only the first [MAX_PROBLEMS] are kept for display. */
    private fun recordProblem(lookup: APathLookup<*>, message: String?) {
        errorCount++
        if (problems.size < MAX_PROBLEMS) problems.add(ScanProblem(lookup.lookedUp, message))
    }

    private fun markIncomplete(key: String) {
        var current = key
        while (current.length > rootKey.length) {
            incomplete.add(current)
            current = parentKey(current)
        }
        incomplete.add(rootKey)
    }

    /**
     * Applies [block] to every directory key from [path]'s parent up to and including the root.
     *
     * The bound is the root key's length, never equality with it: an entry whose ancestors do not
     * actually meet the root (a prefix collision, a followed symlink) must still terminate.
     */
    private inline fun forEachAncestorDir(path: String, block: (String) -> Unit) {
        var current = parentKey(path)
        while (current.length > rootKey.length) {
            block(current)
            current = parentKey(current)
        }
        block(rootKey)
    }

    private fun parentKey(key: String): String = key.substringBeforeLast('/').ifEmpty { "/" }

    private fun isUnderRoot(path: String): Boolean =
        path != rootKey && path.startsWith(if (rootKey == "/") "/" else "$rootKey/")

    companion object {
        const val MAX_PROBLEMS = 500
    }
}
