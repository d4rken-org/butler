package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.archive.crumbsTo
import eu.darken.butler.common.files.local.relativeSegmentsTo
import eu.darken.butler.common.files.saf.crumbsTo
import eu.darken.butler.common.files.smb.crumbsTo
import java.io.File

fun APath<*>.crumbsTo(child: APath<*>): Array<String> {
    require(this::class == child::class)

    return when (this) {
        is LocalPath -> this.relativeSegmentsTo(child as LocalPath)
        is SAFPath -> this.crumbsTo(child as SAFPath)
        is ArchivePath -> this.crumbsTo(child as ArchivePath)
        is SmbPath -> this.crumbsTo(child as SmbPath)
    }
}

fun APath<*>.toFile(): File = when (this) {
    is LocalPath -> this.file
    // Archive entries have no filesystem representation; a File from the synthetic
    // "container!/entry" path would silently point at nothing.
    is ArchivePath -> throw IllegalArgumentException("Archive paths have no File representation: $this")
    // Same reasoning: an SMB path only exists on the server, never in the local filesystem.
    is SmbPath -> throw IllegalArgumentException("SMB paths have no File representation: $this")
    else -> File(this.path)
}

val APath<*>.extension: String?
    get() = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }

/**
 * Finds the common parent directory of all paths in this collection.
 * Returns null if:
 * - The collection is empty
 * - Paths have different types (e.g., LocalPath and SAFPath mixed)
 * - Paths have no common ancestor (e.g., different mount points)
 *
 * For a single path, returns its parent directory.
 */
fun Collection<APath<*>>.commonParent(): APath<*>? {
    if (isEmpty()) return null
    if (size == 1) return first().parent

    // Ensure all paths are the same type
    val firstType = first()::class
    if (!all { it::class == firstType }) return null

    // Archive entries with identical inner segments may come from different archives.
    (first() as? ArchivePath)?.let { firstArchive ->
        if (!all { (it as ArchivePath).container == firstArchive.container }) return null
    }

    // Same for SMB paths, identical segments under two locations are two different servers.
    (first() as? SmbPath)?.let { firstSmb ->
        if (!all { (it as SmbPath).locationId == firstSmb.locationId }) return null
    }

    // Get all segment lists
    val allSegments = map { it.segments }

    // Find the length of the shortest segment list
    val minLength = allSegments.minOfOrNull { it.size } ?: return null

    // Find common prefix length
    var commonPrefixLength = 0
    for (i in 0 until minLength) {
        val segment = allSegments.first()[i]
        if (allSegments.all { it[i] == segment }) {
            commonPrefixLength++
        } else {
            break
        }
    }

    // If no common prefix, no common parent
    if (commonPrefixLength == 0) return null

    // Navigate up from the first path to the common ancestor
    // Start from the first path and go up until we reach the common ancestor level
    var result: APath<*>? = first()
    val targetDepth = commonPrefixLength

    while (result != null && result.segments.size > targetDepth) {
        result = result.parent
    }

    return if (result?.parent == null) null else result
}