package eu.darken.butler.common.parcel

import android.os.Parcel
import kotlinx.parcelize.Parceler
import kotlin.uuid.Uuid

object UuidParceler : Parceler<Uuid> {
    override fun create(parcel: Parcel): Uuid = Uuid.parse(parcel.readString()!!)

    override fun Uuid.write(parcel: Parcel, flags: Int) {
        parcel.writeString(toString())
    }
}