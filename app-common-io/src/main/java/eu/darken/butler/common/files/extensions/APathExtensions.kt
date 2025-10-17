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