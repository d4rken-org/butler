package eu.darken.butler.searcher.core

import android.os.Parcelable
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearchFilter
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class SearchQuery(
    val filenameQuery: FilenameQuery = FilenameQuery(),
    val contentQuery: ContentQuery = ContentQuery(),
    val targets: List<SearchTarget>,
    val options: Options = Options(),
    val filter: SearchFilter = SearchFilter(),
) : Parcelable {

    @Serializable
    @Parcelize
    data class Options(
        val maxResults: Int? = null,
        val followSymlinks: Boolean = false,
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
