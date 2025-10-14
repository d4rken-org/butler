package eu.darken.butler.common.files.metadata

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class FileSystem(
    val freeSpace: Long? = null,
    val totalSpace: Long? = null,
) : Parcelable
