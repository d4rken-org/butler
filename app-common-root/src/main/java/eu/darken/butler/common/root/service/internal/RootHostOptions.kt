package eu.darken.butler.common.root.service.internal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RootHostOptions(
    val isDebug: Boolean = false,
    val isTrace: Boolean = false,
    val recorderPath: String? = null
) : Parcelable {
    companion object {
        fun fromInitArgs(initArgs: RootHostInitArgs) = RootHostOptions(
            isDebug = initArgs.isDebug,
            isTrace = initArgs.isTrace,
            recorderPath = initArgs.recorderPath,
        )
    }
}