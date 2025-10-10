package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.metadata.FileType
import kotlin.time.Instant

data class SAFPathLookup(
    override val lookedUp: SAFPath,
    override val fileType: FileType,
    override val size: Long,
    override val modifiedAt: Instant,
    override val target: LocalPath? = null,
) : APathLookup<SAFPath>