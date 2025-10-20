package eu.darken.butler.common.files

import androidx.annotation.Keep
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlin.time.Instant

@Keep
interface APathLookup<T : APath<T>> {
    val lookedUp: T
    val fileType: FileType

    val size: Long?
    val modifiedAt: Instant?
    val target: APath<*>?

    val ownership: Ownership?
    val permissions: Permissions?
    val createdAt: Instant?

    val error: Throwable?

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