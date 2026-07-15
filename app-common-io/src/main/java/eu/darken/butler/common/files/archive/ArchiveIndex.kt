package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import kotlin.time.Instant

/**
 * Metadata for a single archive entry. [rawName] is the exact name stored in the archive and is
 * what stream-opening resolves against; [segments] are the sanitized path segments used by
 * [ArchivePath]. Entries whose names fail sanitization never make it into the index.
 */
data class ArchiveEntryMeta(
    val segments: List<String>,
    val rawName: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modifiedAt: Instant?,
    val isEncrypted: Boolean = false,
    /** Symlink entry (tar link entries, zip entries with a link unix mode). Never followed. */
    val isSymlink: Boolean = false,
    /** Raw symlink target, if the format stores it in metadata (tar). */
    val linkTarget: String? = null,
    /** True for directory nodes synthesized from entry paths (no explicit archive entry). */
    val synthesized: Boolean = false,
)

data class ArchiveIndex(
    val container: APath<*>,
    val format: ArchiveFormat,
    /** Container stat fingerprint (size+mtime) captured around index construction. */
    val fingerprint: String,
    val entriesBySegments: Map<List<String>, ArchiveEntryMeta>,
    val childrenBySegments: Map<List<String>, List<ArchiveEntryMeta>>,
    /** Entries dropped because their names failed the safety policy (zip-slip etc.). */
    val skippedUnsafe: Int,
    /** Entries dropped because they are unsupported special types (fifo, device, hardlink). */
    val skippedSpecial: Int,
) {
    val isEncrypted: Boolean by lazy { entriesBySegments.values.any { it.isEncrypted } }

    fun childrenOf(segments: List<String>): List<ArchiveEntryMeta> =
        childrenBySegments[segments] ?: emptyList()
}

internal object ArchiveEntrySafety {

    private const val MAX_DEPTH = 64
    private const val MAX_SEGMENT_LENGTH = 255

    /**
     * Parses a raw archive entry name into safe path segments.
     * Returns null for names that must be skipped: absolute paths, traversal segments (`..`),
     * NUL bytes, or excessive depth/length. Redundant separators and `.` segments are dropped.
     */
    fun parseEntryName(rawName: String): List<String>? {
        if (rawName.contains(Char(0))) return null
        // Windows-created archives may use backslash separators; only treat them as such when
        // no forward slashes are present (backslash is a legal filename character on Linux).
        val separator = if (!rawName.contains('/') && rawName.contains('\\')) '\\' else '/'
        val segments = rawName.split(separator).filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.size > MAX_DEPTH) return null
        segments.forEach { segment ->
            if (segment == "..") return null
            if (segment.length > MAX_SEGMENT_LENGTH) return null
            if (separator == '/' && segment.contains('\\')) {
                // Mixed separators are too ambiguous to trust.
                return null
            }
        }
        return segments
    }
}

/**
 * Builds the final index maps from parsed entries: last-wins for duplicate paths, skips
 * file-vs-directory conflicts, and synthesizes implied intermediate directories.
 */
internal fun buildIndexMaps(
    parsed: List<ArchiveEntryMeta>,
): Pair<Map<List<String>, ArchiveEntryMeta>, Map<List<String>, List<ArchiveEntryMeta>>> {
    val bySegments = LinkedHashMap<List<String>, ArchiveEntryMeta>()

    parsed.forEach { entry ->
        val existing = bySegments[entry.segments]
        when {
            existing == null -> bySegments[entry.segments] = entry
            // Explicit directory metadata may enrich a synthesized node.
            existing.synthesized && entry.isDirectory -> bySegments[entry.segments] = entry
            existing.isDirectory != entry.isDirectory ->
                // File-vs-directory conflict: keep the directory (children may depend on it).
                if (existing.isDirectory) Unit else bySegments[entry.segments] = entry
            // Duplicate names: last occurrence wins (matches common unzip behavior).
            else -> bySegments[entry.segments] = entry
        }
    }

    // Synthesize implied parent directories.
    parsed.forEach { entry ->
        for (depth in 1 until entry.segments.size) {
            val dirSegments = entry.segments.subList(0, depth)
            val existing = bySegments[dirSegments]
            if (existing == null) {
                bySegments[dirSegments] = ArchiveEntryMeta(
                    segments = dirSegments.toList(),
                    rawName = dirSegments.joinToString("/") + "/",
                    isDirectory = true,
                    size = null,
                    modifiedAt = null,
                    synthesized = true,
                )
            } else if (!existing.isDirectory) {
                // A file shadows an implied directory: replace it, children win.
                bySegments[dirSegments] = ArchiveEntryMeta(
                    segments = dirSegments.toList(),
                    rawName = dirSegments.joinToString("/") + "/",
                    isDirectory = true,
                    size = null,
                    modifiedAt = null,
                    synthesized = true,
                )
            }
        }
    }

    val children = bySegments.values.groupBy { it.segments.dropLast(1) }
    return bySegments to children
}
