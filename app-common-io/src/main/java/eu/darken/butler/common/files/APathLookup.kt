package eu.darken.butler.common.files

import androidx.annotation.Keep
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.metadata.FileType
import kotlin.time.Instant

@Keep
interface APathLookup<T : APath<T>> {
    val lookedUp: T
    val fileType: FileType
    val size: Long
    val modifiedAt: Instant
    val target: APath<*>?
    val error: String? get() = null

    val path: String
        get() = lookedUp.path
    val name: String
        get() = lookedUp.name
    val userReadablePath: CaString
        get() = lookedUp.userReadablePath
    val userReadableName: CaString
        get() = lookedUp.userReadableName

    val segments: Segments
        get() = lookedUp.segments

    fun child(vararg segments: String): T = lookedUp.child(*segments)
}