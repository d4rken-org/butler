package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.extensions.Segments

/**
 * Path relations between WebDAV paths require the same location: identical segments under two
 * different locations may address two different servers or accounts and are unrelated.
 */

fun WebDavPath.crumbsTo(child: WebDavPath): Array<String> {
    require(this.locationId == child.locationId) { "locations don't match: $locationId <> ${child.locationId}" }
    require(child.segments.size >= segments.size) { "${child.segments} isn't a child of $segments" }
    require(child.segments.subList(0, segments.size) == segments) {
        "Not parent and child: $segments - ${child.segments}"
    }
    return child.segments.subList(segments.size, child.segments.size).toTypedArray()
}

fun WebDavPath.isAncestorOf(child: WebDavPath): Boolean {
    if (this.locationId != child.locationId) return false
    if (this.segments.size >= child.segments.size) return false
    return child.segments.subList(0, segments.size) == segments
}

fun WebDavPath.isParentOf(child: WebDavPath): Boolean {
    if (this.locationId != child.locationId) return false
    if (this.segments.size + 1 != child.segments.size) return false
    return child.segments.dropLast(1) == this.segments
}

fun WebDavPath.matches(other: WebDavPath): Boolean = this == other

fun WebDavPath.startsWith(prefix: WebDavPath): Boolean {
    if (locationId != prefix.locationId) return false
    if (prefix.segments.isEmpty()) return true
    if (this == prefix) return true
    if (segments.size < prefix.segments.size) return false
    val fullPrefixSegments = prefix.segments.dropLast(1)
    if (segments.subList(0, fullPrefixSegments.size) != fullPrefixSegments) return false
    return segments[prefix.segments.size - 1].startsWith(prefix.segments.last())
}

fun WebDavPath.removePrefix(prefix: WebDavPath, overlap: Int = 0): Segments {
    if (!startsWith(prefix)) throw IllegalArgumentException("$prefix is not a prefix of $this")
    return segments.drop(prefix.segments.size - overlap)
}
