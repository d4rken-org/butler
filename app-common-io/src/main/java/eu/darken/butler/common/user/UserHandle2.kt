package eu.darken.butler.common.user

import android.os.Parcel
import android.os.Parcelable
import android.os.UserHandle
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class UserHandle2(
    val handleId: Int = 0
) : Parcelable {

    fun asUserHandle(): UserHandle {
        val userParcel = Parcel.obtain().apply {
            writeInt(handleId)
            setDataPosition(0)
        }
        return UserHandle(userParcel)
    }

}