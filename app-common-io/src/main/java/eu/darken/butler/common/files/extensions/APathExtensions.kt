package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.local.relativeSegmentsTo
import eu.darken.butler.common.files.saf.crumbsTo
import java.io.File

fun APath<*>.crumbsTo(child: APath<*>): Array<String> {
    require(this::class == child::class)

    return when (this) {
        is LocalPath -> this.relativeSegmentsTo(child as LocalPath)
        is SAFPath -> this.crumbsTo(child as SAFPath)
    }
}

fun APath<*>.toFile(): File = when (this) {
    is LocalPath -> this.file
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

    return result
}