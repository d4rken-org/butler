package eu.darken.butler.common.files

import androidx.annotation.Keep
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.extensions.Segments
import kotlin.time.Instant

@Keep
interface APathLookup<out T : APath> {
    val lookedUp: T
    val fileType: FileType
    val size: Long
    val modifiedAt: Instant
    val target: APath?

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

    fun child(vararg segments: String): APath = lookedUp.child(*segments)
}