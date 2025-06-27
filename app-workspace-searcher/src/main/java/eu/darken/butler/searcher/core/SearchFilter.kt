package eu.darken.butler.searcher.core

import android.os.Parcelable
import eu.darken.butler.common.files.FileType
import kotlinx.parcelize.Parcelize
import java.time.Instant

@Parcelize
data class SearchFilter(
    val fileTypes: Set<FileType>? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedAfter: Instant? = null,
    val modifiedBefore: Instant? = null,
    val includePaths: Set<String>? = null,
    val excludePaths: Set<String>? = null,
    val searchHidden: Boolean = false,
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false
) : Parcelable {
    
    fun isEmpty(): Boolean = this == EMPTY
    
    companion object {
        val EMPTY = SearchFilter()
    }
}