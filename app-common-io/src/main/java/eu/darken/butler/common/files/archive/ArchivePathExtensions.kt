package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.ArchivePath

import eu.darken.butler.common.files.extensions.Segments

/**
 * Path relations between archive paths require matching containers (full path identity);
 * entries with identical inner segments in different archives are unrelated.
 */

fun ArchivePath.crumbsTo(child: ArchivePath): Array<String> {
    require(this.container == child.container) { "containers don't match: $container <> ${child.container}" }
    require(child.segments.size >= segments.size) { "${child.segments} isn't a child of $segments" }
    require(child.segments.subList(0, segments.size) == segments) {
        "Not parent and child: $segments - ${child.segments}"
    }
    return child.segments.subList(segments.size, child.segments.size).toTypedArray()
}

fun ArchivePath.isAncestorOf(child: ArchivePath): Boolean {
    if (this.container != child.container) return false
    if (this.segments.size >= child.segments.size) return false
    return child.segments.subList(0, segments.size) == segments
}

fun ArchivePath.isParentOf(child: ArchivePath): Boolean {
    if (this.container != child.container) return false
    if (this.segments.size + 1 != child.segments.size) return false
    return child.segments.dropLast(1) == this.segments
}

fun ArchivePath.startsWith(prefix: ArchivePath): Boolean {
    if (container != prefix.container) return false
    if (prefix.segments.isEmpty()) return true
    if (this == prefix) return true
    if (segments.size < prefix.segments.size) return false
    val fullPrefixSegments = prefix.segments.dropLast(1)
    if (segments.subList(0, fullPrefixSegments.size) != fullPrefixSegments) return false
    return segments[prefix.segments.size - 1].startsWith(prefix.segments.last())
}

fun ArchivePath.removePrefix(prefix: ArchivePath, overlap: Int = 0): Segments {
    if (!startsWith(prefix)) throw IllegalArgumentException("$prefix is not a prefix of $this")
    return segments.drop(prefix.segments.size - overlap)
}
