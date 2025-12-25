package eu.darken.butler.searcher.core

import android.os.Parcelable
import eu.darken.butler.common.files.metadata.FileType
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

    @Serializable
    @Parcelize
    data class Type(
        val fileType: FileType,
    ) : FilterCondition
}

/**
 * A collection of filter conditions to apply during search.
 */
@Serializable
@Parcelize
data class SearchFilter(
    val conditions: List<FilterCondition> = emptyList(),
) : Parcelable {
    val sizeConditions: List<FilterCondition.Size>
        get() = conditions.filterIsInstance<FilterCondition.Size>()

    val dateConditions: List<FilterCondition.ModifiedDate>
        get() = conditions.filterIsInstance<FilterCondition.ModifiedDate>()

    val typeConditions: List<FilterCondition.Type>
        get() = conditions.filterIsInstance<FilterCondition.Type>()

    fun hasConditions(): Boolean = conditions.isNotEmpty()
}
