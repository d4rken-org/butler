package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.workspace.contracts.searcher.FilterComparator
import eu.darken.butler.workspace.contracts.searcher.FilterCondition

/**
 * Shared, backend-independent filter evaluation with explicit tri-state semantics: an item whose
 * metadata is missing evaluates to [Verdict.UNKNOWN], and the include-on-unknown policy (an item
 * is only excluded on a definite [Verdict.NO_MATCH]) is applied in [matchesAll] — files aren't
 * hidden from filtered searches just because the filesystem won't report a field.
 */
object FilterConditionEvaluator {

    enum class Verdict { MATCH, NO_MATCH, UNKNOWN }

    fun matchesAll(conditions: List<FilterCondition>, lookup: APathLookup<*>): Boolean =
        conditions.all { evaluate(it, lookup) != Verdict.NO_MATCH }

    fun evaluate(condition: FilterCondition, lookup: APathLookup<*>): Verdict = when (condition) {
        is FilterCondition.Size -> {
            val size = lookup.size ?: return Verdict.UNKNOWN
            val bytes = condition.bytes.coerceAtLeast(0L)
            val matches = when (condition.comparator) {
                FilterComparator.GT -> size > bytes
                FilterComparator.GTE -> size >= bytes
                FilterComparator.LT -> size < bytes
                FilterComparator.LTE -> size <= bytes
                FilterComparator.EQ -> size == bytes
            }
            if (matches) Verdict.MATCH else Verdict.NO_MATCH
        }
        is FilterCondition.ModifiedDate -> {
            val modifiedAt = lookup.modifiedAt ?: return Verdict.UNKNOWN
            val matches = when (condition.comparator) {
                FilterComparator.GT -> modifiedAt > condition.instant
                FilterComparator.GTE -> modifiedAt >= condition.instant
                FilterComparator.LT -> modifiedAt < condition.instant
                FilterComparator.LTE -> modifiedAt <= condition.instant
                FilterComparator.EQ -> modifiedAt == condition.instant
            }
            if (matches) Verdict.MATCH else Verdict.NO_MATCH
        }
        is FilterCondition.Type ->
            if (lookup.fileType == condition.fileType) Verdict.MATCH else Verdict.NO_MATCH
    }
}
