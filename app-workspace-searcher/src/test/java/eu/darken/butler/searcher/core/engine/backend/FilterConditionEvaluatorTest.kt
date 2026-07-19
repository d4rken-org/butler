package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.engine.backend.FilterConditionEvaluator.Verdict
import eu.darken.butler.workspace.contracts.searcher.FilterComparator
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class FilterConditionEvaluatorTest : BaseTest() {

    private fun lookup(
        size: Long? = null,
        modifiedAt: Instant? = null,
        fileType: FileType = FileType.FILE,
    ) = LocalPathLookup(
        lookedUp = LocalPath.build("/sdcard/test.txt"),
        fileType = fileType,
        size = size,
        modifiedAt = modifiedAt,
        target = null,
    )

    @Nested
    inner class SizeConditions {

        @Test
        fun `comparators evaluate against the item size`() {
            val condition = { comparator: FilterComparator -> FilterCondition.Size(comparator, 100L) }
            val small = lookup(size = 50L)
            val exact = lookup(size = 100L)
            val big = lookup(size = 200L)

            FilterConditionEvaluator.evaluate(condition(FilterComparator.GT), big) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.GT), exact) shouldBe Verdict.NO_MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.GTE), exact) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.GTE), small) shouldBe Verdict.NO_MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.LT), small) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.LT), exact) shouldBe Verdict.NO_MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.LTE), exact) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.LTE), big) shouldBe Verdict.NO_MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.EQ), exact) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.EQ), big) shouldBe Verdict.NO_MATCH
        }

        @Test
        fun `null size is UNKNOWN`() {
            val condition = FilterCondition.Size(FilterComparator.GT, 100L)

            FilterConditionEvaluator.evaluate(condition, lookup(size = null)) shouldBe Verdict.UNKNOWN
        }

        @Test
        fun `negative filter bytes are clamped to zero`() {
            val condition = FilterCondition.Size(FilterComparator.GTE, -50L)

            FilterConditionEvaluator.evaluate(condition, lookup(size = 0L)) shouldBe Verdict.MATCH
        }
    }

    @Nested
    inner class ModifiedDateConditions {

        private val reference = Instant.fromEpochMilliseconds(1_000_000L)

        @Test
        fun `comparators evaluate against the item modification time`() {
            val condition = { comparator: FilterComparator -> FilterCondition.ModifiedDate(comparator, reference) }
            val older = lookup(modifiedAt = Instant.fromEpochMilliseconds(500_000L))
            val same = lookup(modifiedAt = reference)
            val newer = lookup(modifiedAt = Instant.fromEpochMilliseconds(2_000_000L))

            FilterConditionEvaluator.evaluate(condition(FilterComparator.GT), newer) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.GT), same) shouldBe Verdict.NO_MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.GTE), same) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.LT), older) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.LT), same) shouldBe Verdict.NO_MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.LTE), same) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.EQ), same) shouldBe Verdict.MATCH
            FilterConditionEvaluator.evaluate(condition(FilterComparator.EQ), older) shouldBe Verdict.NO_MATCH
        }

        @Test
        fun `null modifiedAt is UNKNOWN`() {
            val condition = FilterCondition.ModifiedDate(FilterComparator.GT, reference)

            FilterConditionEvaluator.evaluate(condition, lookup(modifiedAt = null)) shouldBe Verdict.UNKNOWN
        }
    }

    @Nested
    inner class TypeConditions {

        @Test
        fun `matching file type is MATCH`() {
            val condition = FilterCondition.Type(FileType.DIRECTORY)

            FilterConditionEvaluator.evaluate(condition, lookup(fileType = FileType.DIRECTORY)) shouldBe Verdict.MATCH
        }

        @Test
        fun `different file type is NO_MATCH`() {
            val condition = FilterCondition.Type(FileType.DIRECTORY)

            FilterConditionEvaluator.evaluate(condition, lookup(fileType = FileType.FILE)) shouldBe Verdict.NO_MATCH
        }
    }

    @Nested
    inner class MatchesAll {

        @Test
        fun `empty conditions match everything`() {
            FilterConditionEvaluator.matchesAll(emptyList(), lookup(size = null)) shouldBe true
        }

        @Test
        fun `UNKNOWN verdicts are included`() {
            // Include-on-unknown: files aren't hidden just because the filesystem won't report a field
            val conditions = listOf<FilterCondition>(
                FilterCondition.Size(FilterComparator.GT, 100L),
                FilterCondition.ModifiedDate(FilterComparator.GT, Instant.fromEpochMilliseconds(1_000L)),
            )

            FilterConditionEvaluator.matchesAll(conditions, lookup(size = null, modifiedAt = null)) shouldBe true
        }

        @Test
        fun `a definite NO_MATCH excludes the item`() {
            val conditions = listOf<FilterCondition>(
                FilterCondition.Size(FilterComparator.GT, 100L),
            )

            FilterConditionEvaluator.matchesAll(conditions, lookup(size = 50L)) shouldBe false
        }

        @Test
        fun `mixed MATCH and UNKNOWN still includes the item`() {
            val conditions = listOf<FilterCondition>(
                FilterCondition.Type(FileType.FILE),
                FilterCondition.Size(FilterComparator.GT, 100L),
            )

            FilterConditionEvaluator.matchesAll(conditions, lookup(size = null)) shouldBe true
        }

        @Test
        fun `one NO_MATCH among MATCH and UNKNOWN excludes the item`() {
            val conditions = listOf<FilterCondition>(
                FilterCondition.Type(FileType.FILE),
                FilterCondition.Size(FilterComparator.GT, 100L),
                FilterCondition.ModifiedDate(FilterComparator.GT, Instant.fromEpochMilliseconds(1_000L)),
            )

            FilterConditionEvaluator.matchesAll(conditions, lookup(size = 50L, modifiedAt = null)) shouldBe false
        }
    }
}
