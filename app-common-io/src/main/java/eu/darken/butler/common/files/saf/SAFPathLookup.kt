package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlin.time.Instant

data class SAFPathLookup(
    override val lookedUp: SAFPath,
    override val fileType: FileType,
    override val size: Long?,
    override val modifiedAt: Instant?,
    override val target: LocalPath? = null,
    override val error: String? = null,
    override val ownership: Ownership? = null,
    override val permissions: Permissions? = null,
    override val createdAt: Instant? = null,
) : APathLookup<SAFPath>