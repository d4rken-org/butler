package eu.darken.butler.common.storage

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class StorageId(
    val internalId: String?,
    val externalId: UUID,
) : Parcelable