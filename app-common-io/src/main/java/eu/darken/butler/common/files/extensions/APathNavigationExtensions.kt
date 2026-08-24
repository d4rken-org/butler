package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.archive.isAncestorOf
import eu.darken.butler.common.files.archive.isParentOf
import eu.darken.butler.common.files.archive.removePrefix
import eu.darken.butler.common.files.archive.startsWith
import eu.darken.butler.common.files.local.isAncestorOf
import eu.darken.butler.common.files.local.isParentOf
import eu.darken.butler.common.files.local.removePrefix
import eu.darken.butler.common.files.local.startsWith
import eu.darken.butler.common.files.saf.isAncestorOf
import eu.darken.butler.common.files.saf.isParentOf
import eu.darken.butler.common.files.saf.removePrefix
import eu.darken.butler.common.files.saf.startsWith
import eu.darken.butler.common.files.smb.isAncestorOf
import eu.darken.butler.common.files.smb.isParentOf
import eu.darken.butler.common.files.smb.matches
import eu.darken.butler.common.files.smb.removePrefix
import eu.darken.butler.common.files.smb.startsWith
import java.util.Collections

fun APath<*>.isAncestorOf(descendant: APath<*>): Boolean {
    if (this::class != descendant::class) return false
    return when (this) {
        is LocalPath -> this.isAncestorOf(descendant as LocalPath)
        is SAFPath -> this.isAncestorOf(descendant as SAFPath)
        is ArchivePath -> this.isAncestorOf(descendant as ArchivePath)
        is SmbPath -> this.isAncestorOf(descendant as SmbPath)
    }
}

fun APath<*>.isDescendantOf(ancestor: APath<*>): Boolean {
    if (this::class != ancestor::class) return false
    return ancestor.isAncestorOf(this)
}

fun APath<*>.isDescendantOfOrSelf(ancestor: APath<*>): Boolean {
    return this.matches(ancestor) || this.isDescendantOf(ancestor)
}

fun APath<*>.isAncestorOfOrSelf(descendant: APath<*>): Boolean {
    return this.matches(descendant) || this.isAncestorOf(descendant)
}

/**
 * A parent is a DIRECT ancestor
 * See [isAncestorOf]
 */
fun APath<*>.isParentOf(child: APath<*>): Boolean {
    if (this::class != child::class) return false
    return when (this) {
        is LocalPath -> this.isParentOf(child as LocalPath)
        is SAFPath -> this.isParentOf(child as SAFPath)
        is ArchivePath -> this.isParentOf(child as ArchivePath)
        is SmbPath -> this.isParentOf(child as SmbPath)
    }
}

fun APath<*>.isChildOf(parent: APath<*>): Boolean {
    if (this::class != parent::class) return false
    return parent.isParentOf(this)
}

fun APath<*>.matches(other: APath<*>): Boolean {
    if (this::class != other::class) return false
    return when (this) {
        is LocalPath -> this.path == (other as LocalPath).path
        is SAFPath -> this.path == (other as SAFPath).path
        is ArchivePath -> this == (other as ArchivePath)
        is SmbPath -> this.matches(other as SmbPath)
    }
}

fun APath<*>.containsSegments(vararg target: String): Boolean {
    return Collections.indexOfSubList(this.segments, target.toList()) != -1
}

fun APath<*>.startsWith(prefix: APath<*>): Boolean {
    if (this::class != prefix::class) return false
    return when (this) {
        is LocalPath -> this.startsWith(prefix as LocalPath)
        is SAFPath -> this.startsWith(prefix as SAFPath)
        is ArchivePath -> this.startsWith(prefix as ArchivePath)
        is SmbPath -> this.startsWith(prefix as SmbPath)
    }
}

fun APath<*>.removePrefix(prefix: APath<*>, overlap: Int = 0): Segments {
    if (this::class != prefix::class) {
        throw IllegalArgumentException("removePrefix(): Can't compare different types ($this and $prefix)")
    }
    return when (this) {
        is LocalPath -> this.removePrefix(prefix as LocalPath, overlap)
        is SAFPath -> this.removePrefix(prefix as SAFPath, overlap)
        is ArchivePath -> this.removePrefix(prefix as ArchivePath, overlap)
        is SmbPath -> this.removePrefix(prefix as SmbPath, overlap)
    }
}

fun Collection<APath<*>>.filterDistinctRoots(): Set<APath<*>> = this
    .sortedBy { it.segments.size }
    .fold<APath<*>, Set<APath<*>>>(emptySet()) { acc, path ->
        if (acc.none { it.isAncestorOf(path) }) {
            acc + path
        } else {
            acc
        }
    }
    .toSet()