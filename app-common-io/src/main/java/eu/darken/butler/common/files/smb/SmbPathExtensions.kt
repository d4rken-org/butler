package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.extensions.Segments

/**
 * Path relations between SMB paths require the same location: identical segments under two
 * different locations address two different servers and are unrelated.
 */

fun SmbPath.crumbsTo(child: SmbPath): Array<String> {
    require(this.locationId == child.locationId) { "locations don't match: $locationId <> ${child.locationId}" }
    require(child.segments.size >= segments.size) { "${child.segments} isn't a child of $segments" }
    require(child.segments.subList(0, segments.size) == segments) {
        "Not parent and child: $segments - ${child.segments}"
    }
    return child.segments.subList(segments.size, child.segments.size).toTypedArray()
}

fun SmbPath.isAncestorOf(child: SmbPath): Boolean {
    if (this.locationId != child.locationId) return false
    if (this.segments.size >= child.segments.size) return false
    return child.segments.subList(0, segments.size) == segments
}

fun SmbPath.isParentOf(child: SmbPath): Boolean {
    if (this.locationId != child.locationId) return false
    if (this.segments.size + 1 != child.segments.size) return false
    return child.segments.dropLast(1) == this.segments
}

fun SmbPath.matches(other: SmbPath): Boolean = this == other

fun SmbPath.startsWith(prefix: SmbPath): Boolean {
    if (locationId != prefix.locationId) return false
    if (prefix.segments.isEmpty()) return true
    if (this == prefix) return true
    if (segments.size < prefix.segments.size) return false
    val fullPrefixSegments = prefix.segments.dropLast(1)
    if (segments.subList(0, fullPrefixSegments.size) != fullPrefixSegments) return false
    return segments[prefix.segments.size - 1].startsWith(prefix.segments.last())
}

fun SmbPath.removePrefix(prefix: SmbPath, overlap: Int = 0): Segments {
    if (!startsWith(prefix)) throw IllegalArgumentException("$prefix is not a prefix of $this")
    return segments.drop(prefix.segments.size - overlap)
}
