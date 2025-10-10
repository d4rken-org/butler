package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlin.time.Instant

data class SAFPathLookupExtended(
    val lookup: SAFPathLookup,
    override val ownership: Ownership?,
    override val permissions: Permissions?,
    override val createdAt: Instant?,
) : APathLookupExtended<SAFPath>, APathLookup<SAFPath> by lookup