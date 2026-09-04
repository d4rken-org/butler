package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.parcelize.Parcelize

/**
 * Wire protocol of the streaming walk (`walkStreamV2`).
 *
 * A well-formed stream is any number of [Item]/[DirError] events terminated by exactly one
 * [Done] or [FatalError]. A stream that ends without a terminal event was truncated (host
 * death, broken pipe) and must not be mistaken for a clean completion.
 */
sealed class WalkEvent : Parcelable {

    /** One walked entry. */
    @Parcelize
    data class Item(val lookup: LocalPathLookup) : WalkEvent()

    /** A directory could not be listed; the host skipped it and kept walking. */
    @Parcelize
    data class DirError(val lookup: LocalPathLookup, val message: String?) : WalkEvent()

    /** The walk failed before or during traversal in a way the host could not recover from. */
    @Parcelize
    data class FatalError(val path: LocalPath?, val message: String?) : WalkEvent()

    /** Clean completion marker. */
    @Parcelize
    data object Done : WalkEvent()
}

internal data class WalkEventsIPCWrapper(
    val payload: List<WalkEvent>,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        (parcel.readParcelableArray(WalkEvent::class.java.classLoader)!!.toList() as List<WalkEvent>)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelableArray(payload.toTypedArray(), flags)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<WalkEventsIPCWrapper> {
        override fun createFromParcel(parcel: Parcel): WalkEventsIPCWrapper = WalkEventsIPCWrapper(parcel)

        override fun newArray(size: Int): Array<WalkEventsIPCWrapper?> = arrayOfNulls(size)
    }
}
