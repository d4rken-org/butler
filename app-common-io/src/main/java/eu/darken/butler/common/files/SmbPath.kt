package eu.darken.butler.common.files

import androidx.annotation.Keep
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.smb.SmbLocationInput
import eu.darken.butler.common.parcel.UuidParceler
import eu.darken.butler.common.serialization.UuidSerializer
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Path on an SMB share, addressed relative to the root of a stored network location.
 *
 * [locationId] identifies the location (host, port, share and base directory live there, not here),
 * so renaming or re-authenticating a location never invalidates a stored path. [segments] are
 * relative to that location's root: empty segments mean the location root itself.
 */
@Keep
@Parcelize
@Serializable
@SerialName("SMB")
@TypeParceler<Uuid, UuidParceler>
data class SmbPath(
    val locationId: @Serializable(with = UuidSerializer::class) Uuid,
    override val segments: Segments,
) : APath<SmbPath> {

    init {
        segments.forEach { segment ->
            require(SmbLocationInput.pathSegmentIssue(segment) == null) { "Invalid SMB segment in $segments" }
        }
    }

    override val path: String
        get() = when {
            segments.isEmpty() -> "smb://$locationId"
            else -> "smb://$locationId/${segments.joinToString("/")}"
        }

    override val name: String
        get() = segments.lastOrNull() ?: locationId.toString()

    override val parent: SmbPath?
        get() = if (segments.isEmpty()) null else copy(segments = segments.dropLast(1))

    override fun child(vararg segments: String): SmbPath = copy(segments = this.segments + segments)

    override fun toString(): String = "SmbPath(locationId=$locationId, segments=$segments)"

    companion object {
        fun root(locationId: Uuid): SmbPath = SmbPath(locationId, emptyList())
    }
}
