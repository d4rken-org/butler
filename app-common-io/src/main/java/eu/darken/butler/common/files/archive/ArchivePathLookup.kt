package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.ArchivePath

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
@SerialName("ARCHIVE_LOOKUP")
data class ArchivePathLookup(
    override val lookedUp: ArchivePath,
    override val fileType: FileType,
    override val size: Long?,
    @Contextual override val modifiedAt: Instant?,
    override val target: APath<*>? = null,
    override val error: String? = null,
    override val ownership: Ownership? = null,
    override val permissions: Permissions? = null,
    @Contextual override val createdAt: Instant? = null,
    /** Entry content requires a password to read (listing metadata does not). */
    val isEncrypted: Boolean = false,
) : APathLookup<ArchivePath>
