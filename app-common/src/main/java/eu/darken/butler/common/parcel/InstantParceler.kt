package eu.darken.butler.common.parcel

import android.os.Parcel
import kotlinx.parcelize.Parceler
import kotlin.time.Instant

object InstantParceler : Parceler<Instant> {
    override fun create(parcel: Parcel): Instant {
        return Instant.fromEpochMilliseconds(parcel.readLong())
    }

    override fun Instant.write(parcel: Parcel, flags: Int) {
        parcel.writeLong(toEpochMilliseconds())
    }
}
