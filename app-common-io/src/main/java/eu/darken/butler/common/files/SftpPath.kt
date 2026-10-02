package eu.darken.butler.common.files

import androidx.annotation.Keep
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.network.NetworkLocationNames
import eu.darken.butler.common.parcel.UuidParceler
import eu.darken.butler.common.serialization.UuidSerializer
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import eu.darken.ssh.SftpPath as ServerPath

/**
 * Path on an SFTP server, addressed relative to the root of a stored network location.
 *
 * The root is not part of the path, it is the location's base path as the server resolves it:
 * `SftpPath(id, listOf("photos", "a.jpg"))` under a location whose base path resolves to
 * `/home/darken` is `/home/darken/photos/a.jpg` on the server. Empty segments mean the location root.
 */
@Keep
@Parcelize
@Serializable
@SerialName("SFTP")
@TypeParceler<Uuid, UuidParceler>
data class SftpPath(
    val locationId: @Serializable(with = UuidSerializer::class) Uuid,
    override val segments: Segments,
) : APath<SftpPath> {

    init {
        segments.forEach { segment ->
            require(ServerPath.isValidSegment(segment)) { "Invalid SFTP segment in $segments" }
        }
    }

    override val path: String
        get() = format(locationId.toString())

    override val name: String
        get() = segments.lastOrNull() ?: locationId.toString()

    /** `sftp://cnc-dev/a/b` while the location's name is known, [path] otherwise. */
    override val userReadablePath: CaString
        get() = caString { context ->
            format(NetworkLocationNames.sftp(locationId)?.get(context) ?: locationId.toString())
        }

    override val userReadableName: CaString
        get() = when {
            segments.isEmpty() -> caString { context -> NetworkLocationNames.sftp(locationId)?.get(context) ?: name }
            else -> super.userReadableName
        }

    private fun format(location: String): String = when {
        segments.isEmpty() -> "sftp://$location"
        else -> "sftp://$location/${segments.joinToString("/")}"
    }

    override val parent: SftpPath?
        get() = if (segments.isEmpty()) null else copy(segments = segments.dropLast(1))

    override fun child(vararg segments: String): SftpPath = copy(segments = this.segments + segments)

    override fun toString(): String = "SftpPath(locationId=$locationId, segments=$segments)"

    companion object {
        fun root(locationId: Uuid): SftpPath = SftpPath(locationId, emptyList())
    }
}
