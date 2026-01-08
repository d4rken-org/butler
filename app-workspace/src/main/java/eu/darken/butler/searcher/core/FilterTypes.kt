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

val FilterComparator.isLowerBound: Boolean
    get() = this == FilterComparator.GT || this == FilterComparator.GTE

val FilterComparator.isUpperBound: Boolean
    get() = this == FilterComparator.LT || this == FilterComparator.LTE

val FilterComparator.isExact: Boolean
    get() = this == FilterComparator.EQ

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

    /**
     * Adds a condition with smart merging:
     * - Size/Date with same direction: keeps most restrictive
     * - Size/Date with opposite direction: allows both (valid range) or replaces (invalid range)
     * - Type: replaces any existing type condition
     * - EQ: replaces all conditions of the same type
     */
    fun withCondition(newCondition: FilterCondition): SearchFilter {
        val mergedConditions = when (newCondition) {
            is FilterCondition.Type -> mergeTypeCondition(newCondition)
            is FilterCondition.Size -> mergeSizeCondition(newCondition)
            is FilterCondition.ModifiedDate -> mergeDateCondition(newCondition)
        }
        return copy(conditions = mergedConditions)
    }

    private fun mergeTypeCondition(newCondition: FilterCondition.Type): List<FilterCondition> =
        conditions.filterNot { it is FilterCondition.Type } + newCondition

    private fun mergeSizeCondition(newCondition: FilterCondition.Size): List<FilterCondition> =
        mergeRangeCondition(
            newCondition = newCondition,
            isMatch = { it is FilterCondition.Size },
            getComparator = { (it as FilterCondition.Size).comparator },
            isMoreRestrictiveLowerBound = { existing ->
                newCondition.bytes >= (existing as FilterCondition.Size).bytes
            },
            isMoreRestrictiveUpperBound = { existing ->
                newCondition.bytes <= (existing as FilterCondition.Size).bytes
            },
            wouldCreateInvalidRange = { existingOpposite ->
                val existingBytes = (existingOpposite as FilterCondition.Size).bytes
                if (newCondition.comparator.isLowerBound) {
                    newCondition.bytes > existingBytes
                } else {
                    newCondition.bytes < existingBytes
                }
            },
        )

    private fun mergeDateCondition(newCondition: FilterCondition.ModifiedDate): List<FilterCondition> =
        mergeRangeCondition(
            newCondition = newCondition,
            isMatch = { it is FilterCondition.ModifiedDate },
            getComparator = { (it as FilterCondition.ModifiedDate).comparator },
            isMoreRestrictiveLowerBound = { existing ->
                newCondition.instant >= (existing as FilterCondition.ModifiedDate).instant
            },
            isMoreRestrictiveUpperBound = { existing ->
                newCondition.instant <= (existing as FilterCondition.ModifiedDate).instant
            },
            wouldCreateInvalidRange = { existingOpposite ->
                val existingInstant = (existingOpposite as FilterCondition.ModifiedDate).instant
                if (newCondition.comparator.isLowerBound) {
                    newCondition.instant > existingInstant
                } else {
                    newCondition.instant < existingInstant
                }
            },
        )

    private fun <T : FilterCondition> mergeRangeCondition(
        newCondition: T,
        isMatch: (FilterCondition) -> Boolean,
        getComparator: (FilterCondition) -> FilterComparator,
        isMoreRestrictiveLowerBound: (FilterCondition) -> Boolean,
        isMoreRestrictiveUpperBound: (FilterCondition) -> Boolean,
        wouldCreateInvalidRange: (FilterCondition) -> Boolean,
    ): List<FilterCondition> {
        val newComparator = getComparator(newCondition)

        // EQ is exclusive - replaces all conditions of this type
        if (newComparator.isExact) {
            return conditions.filterNot { isMatch(it) } + newCondition
        }

        val existingConditions = conditions.filter { isMatch(it) }
        val otherConditions = conditions.filterNot { isMatch(it) }

        // Check if there's an existing EQ - replace if adding any new condition
        val existingEq = existingConditions.find { getComparator(it).isExact }
        if (existingEq != null) {
            return otherConditions + newCondition
        }

        // Check if there's an existing condition in the same direction
        val existingInSameDirection = existingConditions.find { existing ->
            val existingComparator = getComparator(existing)
            (newComparator.isLowerBound && existingComparator.isLowerBound) ||
                (newComparator.isUpperBound && existingComparator.isUpperBound)
        }

        // Check if there's an existing condition in the opposite direction
        val existingInOppositeDirection = existingConditions.find { existing ->
            val existingComparator = getComparator(existing)
            (newComparator.isLowerBound && existingComparator.isUpperBound) ||
                (newComparator.isUpperBound && existingComparator.isLowerBound)
        }

        return when {
            existingInSameDirection != null -> {
                val isMoreRestrictive = if (newComparator.isLowerBound) {
                    isMoreRestrictiveLowerBound(existingInSameDirection)
                } else {
                    isMoreRestrictiveUpperBound(existingInSameDirection)
                }

                if (isMoreRestrictive) {
                    // Replace existing with new (more restrictive)
                    otherConditions + existingConditions.filterNot { it == existingInSameDirection } + newCondition
                } else {
                    // Keep existing (already more restrictive)
                    conditions
                }
            }
            // Opposite direction exists - check for invalid range
            existingInOppositeDirection != null -> {
                if (wouldCreateInvalidRange(existingInOppositeDirection)) {
                    // Invalid range - new condition replaces the conflicting one
                    otherConditions + existingConditions.filterNot { it == existingInOppositeDirection } + newCondition
                } else {
                    // Valid range - keep both
                    conditions + newCondition
                }
            }
            // No existing conditions of same type - just add
            else -> conditions + newCondition
        }
    }
}
