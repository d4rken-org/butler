package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
@SerialName("SFTP_LOOKUP")
data class SftpPathLookup(
    override val lookedUp: SftpPath,
    override val fileType: FileType,
    override val size: Long?,
    @Contextual override val modifiedAt: Instant?,
    /** Where a symbolic link points, if that is inside the same location. */
    override val target: SftpPath? = null,
    override val error: String? = null,
    override val ownership: Ownership? = null,
    override val permissions: Permissions? = null,
    @Contextual override val createdAt: Instant? = null,
    /** A symbolic link's target as the server stores it, e.g. `../shared` or `/srv/media`. */
    val linkTarget: String? = null,
) : APathLookup<SftpPath>
