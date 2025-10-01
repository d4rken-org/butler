package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.files.RawPath
import java.io.File


fun RawPath.crumbsTo(child: RawPath): Array<String> {
    val childPath = child.path
    val parentPath = this.path
    val pure = childPath.replaceFirst(parentPath, "")
    return pure.split(File.separatorChar)
        .filter { it.isNotEmpty() }
        .toTypedArray()
}


fun RawPath.isAncestorOf(child: RawPath): Boolean {
    val parentPath = this.toFile().absolutePath
    val childPath = child.toFile().absolutePath

    return childPath.startsWith(parentPath + File.separator)
}