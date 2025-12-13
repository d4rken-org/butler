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
    val filenameQuery: FilenameQuery = FilenameQuery(),
    val contentQuery: ContentQuery = ContentQuery(),
    val targets: List<SearchTarget>,
    val options: Options = Options(),
    val filter: Filter = Filter(),
) : Parcelable {

    init {
        require(targets.isNotEmpty()) { "Search targets list must not be empty" }
    }

    @Serializable
    @Parcelize
    data class Options(
        val maxResults: Int? = null,
        val followSymlinks: Boolean = false,
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
    ) : Parcelable

    companion object {
        fun create(
            paths: List<APath<*>>,
            filenameQuery: FilenameQuery = FilenameQuery(),
            contentQuery: ContentQuery = ContentQuery(),
            maxResults: Int? = null,
        ) = SearchQuery(
            filenameQuery = filenameQuery,
            contentQuery = contentQuery,
            targets = paths.map { SearchTarget.Path.from(it) },
            options = Options(maxResults = maxResults),
        )
    }
}