package eu.darken.butler.searcher.core

import android.os.Parcelable
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import kotlin.time.Instant

@Parcelize
data class SearchResult(
    val lookup: @RawValue APathLookup<*>,
    val matchedQuery: String,
    val matchContext: MatchContext? = null
) : Parcelable {
    
    val path: APath
        get() = lookup.lookedUp
    
    val name: String
        get() = lookup.lookedUp.name
        
    val fileType: FileType
        get() = lookup.fileType
        
    val size: Long?
        get() = lookup.size
        
    val modifiedAt: Instant?
        get() = lookup.modifiedAt
    
    @Parcelize
    data class MatchContext(
        val lineNumber: Int? = null,
        val matchedLine: String? = null,
        val startIndex: Int? = null,
        val endIndex: Int? = null
    ) : Parcelable
    
    companion object {
        fun fromLookup(
            lookup: APathLookup<*>,
            matchedQuery: String,
            matchContext: MatchContext? = null
        ): SearchResult = SearchResult(
            lookup = lookup,
            matchedQuery = matchedQuery,
            matchContext = matchContext
        )
    }
}