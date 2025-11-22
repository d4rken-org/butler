package eu.darken.butler.common.files.metadata

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Keep
@Parcelize
@Serializable
enum class FileType : Parcelable {
    DIRECTORY, SYMBOLIC_LINK, FILE, UNKNOWN
}