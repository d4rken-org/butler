package eu.darken.butler.common.parcel

import android.os.Parcel
import kotlinx.parcelize.Parceler
import java.util.UUID

object UUIDParceler : Parceler<UUID> {
    override fun create(parcel: Parcel): UUID = UUID.fromString(parcel.readString())

    override fun UUID.write(parcel: Parcel, flags: Int) {
        parcel.writeString(toString())
    }
}