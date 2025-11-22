package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
@SerialName("SAF_LOOKUP")
data class SAFPathLookup(
    override val lookedUp: SAFPath,
    override val fileType: FileType,
    override val size: Long?,
    @Contextual override val modifiedAt: Instant?,
    override val target: LocalPath? = null,
    override val error: String? = null,
    override val ownership: Ownership? = null,
    override val permissions: Permissions? = null,
    @Contextual override val createdAt: Instant? = null,
) : APathLookup<SAFPath>