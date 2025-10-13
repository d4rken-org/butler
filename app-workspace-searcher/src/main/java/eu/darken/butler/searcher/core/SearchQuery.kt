package eu.darken.butler.searcher.core

import android.os.Parcelable
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.metadata.FileType
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
@Parcelize
data class SearchQuery(
    val query: String,
    val path: APath<*>,
    val options: Options = Options(),
    val filter: Filter = Filter()
) : Parcelable {

    @Serializable
    @Parcelize
    data class Options(
        val searchContent: Boolean = false,
        val maxResults: Int? = null,
        val followSymlinks: Boolean = false
    ) : Parcelable

    @Serializable
    @Parcelize
    data class Filter(
        // File type and size filters
        val fileTypes: Set<FileType>? = null,
        val minSize: Long? = null,
        val maxSize: Long? = null,
        
        // Date filters
        @Contextual val modifiedAfter: Instant? = null,
        @Contextual val modifiedBefore: Instant? = null,
        
        // Path filters
        val includePaths: Set<String>? = null,
        val excludePaths: Set<String>? = null,
        val searchHidden: Boolean = false,
        
        // Search mode filters
        val caseSensitive: Boolean = false,
        val useRegex: Boolean = false,
        val wholeWord: Boolean = false
    ) : Parcelable
    
    companion object {
        fun create(
            query: String,
            path: APath<*>,
            searchContent: Boolean = false,
            caseSensitive: Boolean = false,
            useRegex: Boolean = false,
            wholeWord: Boolean = false,
            maxResults: Int? = null
        ) = SearchQuery(
            query = query,
            path = path,
            options = Options(
                searchContent = searchContent,
                maxResults = maxResults
            ),
            filter = Filter(
                caseSensitive = caseSensitive,
                useRegex = useRegex,
                wholeWord = wholeWord
            )
        )
    }
}