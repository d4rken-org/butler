package eu.darken.butler.searcher.core

import android.os.Parcelable
import eu.darken.butler.common.files.APath
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Comparator for filter conditions.
 */
@Serializable
enum class FilterComparator(val symbol: String) {
    GT(">"),    // Greater than
    GTE("≥"),   // Greater than or equal
    LT("<"),    // Less than
    LTE("≤"),   // Less than or equal
    EQ("="),    // Equal
}

/**
 * A single filter condition with type, comparator, and value.
 */
@Serializable
sealed interface FilterCondition : Parcelable {
    @Serializable
    @Parcelize
    data class Size(
        val comparator: FilterComparator,
        val bytes: Long,
    ) : FilterCondition

    @Serializable
    @Parcelize
    data class ModifiedDate(
        val comparator: FilterComparator,
        @Contextual val instant: Instant,
    ) : FilterCondition
}

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
        val conditions: List<FilterCondition> = emptyList(),
    ) : Parcelable {
        val sizeConditions: List<FilterCondition.Size>
            get() = conditions.filterIsInstance<FilterCondition.Size>()

        val dateConditions: List<FilterCondition.ModifiedDate>
            get() = conditions.filterIsInstance<FilterCondition.ModifiedDate>()

        fun hasConditions(): Boolean = conditions.isNotEmpty()
    }

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
