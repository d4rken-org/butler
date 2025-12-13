package eu.darken.butler.searcher.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class FilenameQuery(
    val pattern: String = "",
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWord: Boolean = false,
) : Parcelable {
    val isEmpty: Boolean get() = pattern.isBlank()
    val isNotEmpty: Boolean get() = pattern.isNotBlank()
}

@Serializable
@Parcelize
data class ContentQuery(
    val pattern: String = "",
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWord: Boolean = false,
) : Parcelable {
    val isEmpty: Boolean get() = pattern.isBlank()
    val isNotEmpty: Boolean get() = pattern.isNotBlank()
}
