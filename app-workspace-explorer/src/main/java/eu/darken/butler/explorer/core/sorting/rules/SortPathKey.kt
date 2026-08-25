package eu.darken.butler.explorer.core.sorting.rules

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath

/**
 * Stable identity of a folder for the purposes of a saved sort rule.
 *
 * Keys are built from a component list, never from the raw path string: ancestors are the truncated
 * component list, so `PhotosBackup` can never resolve as a child of `Photos` the way a string prefix
 * comparison would. Components are individually escaped (`%`, `/` and `!`) so a folder legitimately
 * named `Budget:2026` cannot collide with `Budget/2026`, and so the archive marker - the only
 * unescaped `!` a key can contain - cannot collide with a real filename containing `!`.
 *
 * SAF keys are deliberately grant-independent: the document ID is split on `:` the way
 * [SAFPath.userReadablePath] splits colon-form and bare-volume IDs, so a broad `primary:` grant and
 * a narrow `primary:Pictures/Trips` grant of the same folder produce the same key AND the same
 * ancestor list. A path-shaped ID has no volume part and simply becomes the whole `storageId`.
 */
fun APath<*>.sortPathKey(): String = keyComponents().components.joinKey()

/**
 * The folder's own key followed by its ancestors, nearest first - index 0 is the folder itself.
 *
 * Truncation stops at the type's floor, so ancestors never walk out of their container: a subtree
 * rule on `/Downloads` does not reach inside `/Downloads/foo.zip`, and a SAF key never climbs past
 * the volume root.
 */
fun APath<*>.sortAncestorKeys(): List<String> {
    val (components, floor) = keyComponents()
    return (components.size downTo floor).map { size -> components.take(size).joinKey() }
}

private const val LOCAL_PREFIX = "local"
private const val SAF_PREFIX = "saf"
private const val ARCHIVE_MARKER = "!archive"

/** [floor] is the smallest component count a key of this type may have. */
private data class KeyComponents(
    val components: List<String>,
    val floor: Int,
)

private fun APath<*>.keyComponents(): KeyComponents = when (this) {
    is LocalPath -> KeyComponents(
        components = (listOf(LOCAL_PREFIX) + normalizedLocalSegments(file.path)).escapeComponents(),
        floor = 1,
    )

    is SAFPath -> {
        val documentId = treeRootUri.path?.let { TREE_DOCUMENT_ID_REGEX.matchEntire(it)?.groupValues?.get(1) }
        // Split once on ':', then split the base path on '/'
        val parts = documentId?.split(":", limit = 2)
        val storageId = parts?.getOrNull(0) ?: treeRootUri.rawUri
        val basePath = parts?.getOrNull(1)?.split("/")?.filter { it.isNotEmpty() } ?: emptyList()
        KeyComponents(
            components = (listOf(SAF_PREFIX, treeRootUri.authority ?: "", storageId) + basePath + segments)
                .escapeComponents(),
            floor = 3,
        )
    }

    is ArchivePath -> {
        val containerKey = container.keyComponents()
        // The marker is a reserved literal and deliberately bypasses escaping: every path-derived
        // component has its '!' escaped, so no real folder can ever produce it.
        KeyComponents(
            components = containerKey.components + ARCHIVE_MARKER + segments.escapeComponents(),
            floor = containerKey.components.size + 1,
        )
    }
}

/**
 * Lexical normalization only - `.` and `..` are resolved without touching the file system, so the
 * key of a path is a pure function of the path. [LocalPath] is always absolute, so a leading `..`
 * can only be dropped.
 */
private fun normalizedLocalSegments(path: String): List<String> {
    val resolved = ArrayDeque<String>()
    path.split('/').forEach { segment ->
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> if (resolved.isNotEmpty()) resolved.removeLast()
            else -> resolved.addLast(segment)
        }
    }
    return resolved.toList()
}

/** Components are stored escaped, so the archive marker can be joined in as a literal. */
private fun List<String>.joinKey(): String = joinToString("/")

private fun List<String>.escapeComponents(): List<String> = map { it.escapeComponent() }

/** '%' first, so the escaping stays reversible. */
private fun String.escapeComponent(): String = replace("%", "%25")
    .replace("/", "%2F")
    .replace("!", "%21")

private val TREE_DOCUMENT_ID_REGEX by lazy { Regex("^/tree/(.+)$") }
