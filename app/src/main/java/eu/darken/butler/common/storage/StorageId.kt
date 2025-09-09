package eu.darken.butler.common.storage

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.uuid.Uuid

@Parcelize
data class StorageId(
    val internalId: String?,
    val externalId: Uuid,
) : Parcelable