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
import eu.darken.webdav.WebDavPath as ServerPath

/**
 * Path on a WebDAV server, addressed relative to the collection URL of a stored network location.
 *
 * `WebDavPath(id, listOf("photos", "a.jpg"))` under a location with the URL
 * `https://cloud.example.com/remote.php/dav/files/alice/` addresses
 * `https://cloud.example.com/remote.php/dav/files/alice/photos/a.jpg`. Empty segments mean the location root.
 */
@Keep
@Parcelize
@Serializable
@SerialName("WEBDAV")
@TypeParceler<Uuid, UuidParceler>
data class WebDavPath(
    val locationId: @Serializable(with = UuidSerializer::class) Uuid,
    override val segments: Segments,
) : APath<WebDavPath> {

    init {
        segments.forEach { segment ->
            require(ServerPath.isValidSegment(segment)) { "Invalid WebDAV segment in $segments" }
        }
    }

    override val path: String
        get() = format(locationId.toString())

    override val name: String
        get() = segments.lastOrNull() ?: locationId.toString()

    /** `webdav://Nextcloud/a/b` while the location's name is known, [path] otherwise. */
    override val userReadablePath: CaString
        get() = caString { context ->
            format(NetworkLocationNames.webdav(locationId)?.get(context) ?: locationId.toString())
        }

    override val userReadableName: CaString
        get() = when {
            segments.isEmpty() -> caString { context -> NetworkLocationNames.webdav(locationId)?.get(context) ?: name }
            else -> super.userReadableName
        }

    private fun format(location: String): String = when {
        segments.isEmpty() -> "webdav://$location"
        else -> "webdav://$location/${segments.joinToString("/")}"
    }

    override val parent: WebDavPath?
        get() = if (segments.isEmpty()) null else copy(segments = segments.dropLast(1))

    override fun child(vararg segments: String): WebDavPath = copy(segments = this.segments + segments)

    override fun toString(): String = "WebDavPath(locationId=$locationId, segments=$segments)"

    companion object {
        fun root(locationId: Uuid): WebDavPath = WebDavPath(locationId, emptyList())
    }
}
