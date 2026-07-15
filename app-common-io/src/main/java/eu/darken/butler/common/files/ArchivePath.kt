package eu.darken.butler.common.files

import androidx.annotation.Keep

import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Virtual path addressing an entry INSIDE an archive file (zip, tar, tar.gz, tar.bz2).
 *
 * [container] is the real path of the archive file itself (a [eu.darken.butler.common.files.LocalPath]
 * or [eu.darken.butler.common.files.SAFPath]; nested archive containers are rejected by the gateway).
 * [segments] address an entry within the archive, empty segments meaning the archive root.
 *
 * Archive contents are read-only: all write operations through the gateway fail.
 */
@Keep
@Parcelize
@Serializable
@SerialName("ARCHIVE")
data class ArchivePath(
    val container: APath<*>,
    override val segments: List<String>,
) : APath<ArchivePath> {

    init {
        // Safety net against hostile entry names (zip-slip) surviving construction or deserialization.
        segments.forEach { segment ->
            require(segment.isNotEmpty()) { "Empty segment in $segments" }
            require(segment != "." && segment != "..") { "Traversal segment in $segments" }
            require(!segment.contains('/') && !segment.contains('\\')) { "Separator in segment: $segment" }
            require(!segment.contains('\u0000')) { "NUL byte in segment" }
        }
    }

    override val path: String
        get() = when {
            segments.isEmpty() -> "${container.path}!"
            else -> "${container.path}!/${segments.joinToString("/")}"
        }

    override val name: String
        get() = segments.lastOrNull() ?: container.name

    override val parent: ArchivePath?
        get() = if (segments.isEmpty()) null else copy(segments = segments.dropLast(1))

    override fun child(vararg segments: String): ArchivePath = copy(segments = this.segments + segments)

    override fun toString(): String = "ArchivePath(container=$container, segments=$segments)"

    companion object {
        fun root(container: APath<*>): ArchivePath = ArchivePath(container, emptyList())
    }
}
