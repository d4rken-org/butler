package eu.darken.butler.common.picker.core

import android.os.Parcelable
import eu.darken.butler.common.files.APath
import kotlinx.parcelize.Parcelize

sealed class FilePickerResult : Parcelable {
    @Parcelize
    data class Selected(val paths: List<APath>) : FilePickerResult()
    
    @Parcelize
    data object Cancelled : FilePickerResult()
}