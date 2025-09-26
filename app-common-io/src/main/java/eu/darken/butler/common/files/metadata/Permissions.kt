package eu.darken.butler.common.files.metadata

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.toOctal
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Permissions(
    val mode: Int
) : Parcelable {
    @IgnoredOnParcel @Transient val octal: String = mode.toOctal()

    constructor(parcel: Parcel) : this(parcel.readInt())

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(mode)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "Permission($octal)"

    companion object CREATOR : Parcelable.Creator<Permissions> {
        override fun createFromParcel(parcel: Parcel): Permissions {
            return Permissions(parcel)
        }

        override fun newArray(size: Int): Array<Permissions?> {
            return arrayOfNulls(size)
        }
    }
}