package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import eu.darken.butler.common.files.Ownership
import eu.darken.butler.common.files.Permissions

data class SAFPathLookupExtended(
    val lookup: SAFPathLookup,
) : APathLookupExtended<SAFPath>, APathLookup<SAFPath> by lookup {

    private val fstat by lazy { lookup.docFile.fstat() }
    override val ownership: Ownership? by lazy {
        fstat?.let { Ownership(it.st_uid.toLong(), it.st_gid.toLong()) }
    }
    override val permissions: Permissions? by lazy {
        fstat?.let { Permissions(it.st_mode) }
    }
}