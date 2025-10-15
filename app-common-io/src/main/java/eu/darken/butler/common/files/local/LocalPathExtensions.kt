package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.extensions.toFile
import java.io.File


fun LocalPath.relativeSegmentsTo(child: LocalPath): Array<String> {
    val childPath = child.path
    val parentPath = this.path
    val pure = childPath.replaceFirst(parentPath, "")
    return pure.split(File.separatorChar)
        .filter { it.isNotEmpty() }
        .toTypedArray()
}

fun LocalPath.toCrumbs(): List<LocalPath> {
    val crumbs = mutableListOf<LocalPath>()
    crumbs.add(this)
    var parent = this.toFile().parentFile
    while (parent != null) {
        crumbs.add(0, LocalPath.build(parent))
        parent = parent.parentFile
    }
    return crumbs
}

fun LocalPath.toNioPath(): java.nio.file.Path = file.toPath()

fun LocalPath.isAncestorOf(child: LocalPath): Boolean {
    val parentPath = this.toFile().path
    val childPath = child.toFile().path

    return when {
        parentPath.length >= childPath.length -> false
        !childPath.startsWith(parentPath) -> false
        parentPath == File.separator -> true
        else -> childPath[parentPath.length] == File.separatorChar
    }
}

fun LocalPath.isParentOf(child: LocalPath): Boolean {
    return isAncestorOf(child) && child(child.name) == child
}

fun LocalPath.startsWith(prefix: LocalPath): Boolean {
    if (this == prefix) return true
    if (segments.size < prefix.segments.size) return false

    return when {
        prefix.segments.size == 1 -> {
            segments.first().startsWith(prefix.segments.first())
        }

        segments.size == prefix.segments.size -> {
            val match = prefix.segments.dropLast(1) == segments.dropLast(1)
            match && segments.last().startsWith(prefix.segments.last())
        }

        else -> {
            val match = prefix.segments.dropLast(1) == segments.dropLast(segments.size - prefix.segments.size + 1)
            match && segments[prefix.segments.size - 1].startsWith(prefix.segments.last())
        }
    }
}

fun LocalPath.removePrefix(prefix: LocalPath, overlap: Int = 0): Segments {
    if (!startsWith(prefix)) throw IllegalArgumentException("$prefix is not a prefix of $this")
    return segments.drop(prefix.segments.size - overlap)
}