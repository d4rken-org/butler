package eu.darken.butler.common.files.local

import android.os.Parcelable
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlinx.parcelize.Parcelize

@Parcelize
data class LocalPathLookupExtended(
    val lookup: LocalPathLookup,
    override val ownership: Ownership?,
    override val permissions: Permissions?,
) : APathLookupExtended<LocalPath>, APathLookup<LocalPath> by lookup, Parcelable