package eu.darken.butler.searcher.core

import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class FilterConditionMergeTest : BaseTest() {

    @Nested
    inner class SizeConditionTests {

        @Test
        fun `adding GTE replaces smaller GTE - keep most restrictive lower bound`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.GTE, 1_000_000) // >= 1MB
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.GTE, 3_000_000) // >= 3MB
            )

            result.conditions shouldHaveSize 1
            result.sizeConditions.first().bytes shouldBe 3_000_000
        }

        @Test
        fun `adding smaller GTE is ignored - existing is more restrictive`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.GTE, 5_000_000) // >= 5MB
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.GTE, 1_000_000) // >= 1MB
            )

            result.conditions shouldHaveSize 1
            result.sizeConditions.first().bytes shouldBe 5_000_000
        }

        @Test
        fun `adding LTE replaces larger LTE - keep most restrictive upper bound`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.LTE, 10_000_000) // <= 10MB
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.LTE, 5_000_000) // <= 5MB
            )

            result.conditions shouldHaveSize 1
            result.sizeConditions.first().bytes shouldBe 5_000_000
        }

        @Test
        fun `adding larger LTE is ignored - existing is more restrictive`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.LTE, 5_000_000) // <= 5MB
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.LTE, 10_000_000) // <= 10MB
            )

            result.conditions shouldHaveSize 1
            result.sizeConditions.first().bytes shouldBe 5_000_000
        }

        @Test
        fun `GTE and LTE can coexist - creates valid range`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.GTE, 1_000_000) // >= 1MB
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.LTE, 10_000_000) // <= 10MB
            )

            result.conditions shouldHaveSize 2
            result.sizeConditions.map { it.comparator } shouldContainExactly listOf(
                FilterComparator.GTE,
                FilterComparator.LTE,
            )
        }

        @Test
        fun `GT replaces GTE in same direction when more restrictive`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.GTE, 1_000_000)
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.GT, 1_000_000)
            )

            result.conditions shouldHaveSize 1
            result.sizeConditions.first().comparator shouldBe FilterComparator.GT
        }

        @Test
        fun `EQ replaces all size conditions`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.GTE, 1_000_000),
                    FilterCondition.Size(FilterComparator.LTE, 10_000_000),
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.EQ, 5_000_000)
            )

            result.conditions shouldHaveSize 1
            result.sizeConditions.first().comparator shouldBe FilterComparator.EQ
            result.sizeConditions.first().bytes shouldBe 5_000_000
        }

        @Test
        fun `GTE replaces existing EQ`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.EQ, 5_000_000)
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.GTE, 1_000_000)
            )

            result.conditions shouldHaveSize 1
            result.sizeConditions.first().comparator shouldBe FilterComparator.GTE
        }

        @Test
        fun `invalid range - LTE smaller than GTE replaces GTE`() {
            // >= 3MB exists, adding <= 1MB would create invalid range
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.GTE, 3_000_000) // >= 3MB
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.LTE, 1_000_000) // <= 1MB
            )

            result.conditions shouldHaveSize 1
            result.sizeConditions.first().comparator shouldBe FilterComparator.LTE
            result.sizeConditions.first().bytes shouldBe 1_000_000
        }

        @Test
        fun `invalid range - GTE larger than LTE replaces LTE`() {
            // <= 1MB exists, adding >= 3MB would create invalid range
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.LTE, 1_000_000) // <= 1MB
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.GTE, 3_000_000) // >= 3MB
            )

            result.conditions shouldHaveSize 1
            result.sizeConditions.first().comparator shouldBe FilterComparator.GTE
            result.sizeConditions.first().bytes shouldBe 3_000_000
        }

        @Test
        fun `boundary case - equal values create valid range`() {
            // >= 5MB and <= 5MB is valid (matches exactly 5MB)
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.GTE, 5_000_000) // >= 5MB
                )
            )

            val result = initial.withCondition(
                FilterCondition.Size(FilterComparator.LTE, 5_000_000) // <= 5MB
            )

            result.conditions shouldHaveSize 2
        }
    }

    @Nested
    inner class DateConditionTests {

        private val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        private val sevenDaysAgo = now - 7.days
        private val thirtyDaysAgo = now - 30.days
        private val oneYearAgo = now - 365.days

        @Test
        fun `adding more recent GT replaces older GT - keep most restrictive lower bound`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.ModifiedDate(FilterComparator.GT, thirtyDaysAgo)
                )
            )

            val result = initial.withCondition(
                FilterCondition.ModifiedDate(FilterComparator.GT, sevenDaysAgo)
            )

            result.conditions shouldHaveSize 1
            result.dateConditions.first().instant shouldBe sevenDaysAgo
        }

        @Test
        fun `adding older GT is ignored - existing is more restrictive`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.ModifiedDate(FilterComparator.GT, sevenDaysAgo)
                )
            )

            val result = initial.withCondition(
                FilterCondition.ModifiedDate(FilterComparator.GT, thirtyDaysAgo)
            )

            result.conditions shouldHaveSize 1
            result.dateConditions.first().instant shouldBe sevenDaysAgo
        }

        @Test
        fun `adding older LT replaces newer LT - keep most restrictive upper bound`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.ModifiedDate(FilterComparator.LT, thirtyDaysAgo)
                )
            )

            val result = initial.withCondition(
                FilterCondition.ModifiedDate(FilterComparator.LT, oneYearAgo)
            )

            result.conditions shouldHaveSize 1
            result.dateConditions.first().instant shouldBe oneYearAgo
        }

        @Test
        fun `adding newer LT is ignored - existing is more restrictive`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.ModifiedDate(FilterComparator.LT, oneYearAgo)
                )
            )

            val result = initial.withCondition(
                FilterCondition.ModifiedDate(FilterComparator.LT, thirtyDaysAgo)
            )

            result.conditions shouldHaveSize 1
            result.dateConditions.first().instant shouldBe oneYearAgo
        }

        @Test
        fun `GT and LT can coexist - creates valid date range`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.ModifiedDate(FilterComparator.GT, oneYearAgo)
                )
            )

            val result = initial.withCondition(
                FilterCondition.ModifiedDate(FilterComparator.LT, sevenDaysAgo)
            )

            result.conditions shouldHaveSize 2
        }

        @Test
        fun `invalid range - LT older than GT replaces GT`() {
            // "after 7 days ago" exists, adding "before 30 days ago" would create invalid range
            val initial = SearchFilter(
                listOf(
                    FilterCondition.ModifiedDate(FilterComparator.GT, sevenDaysAgo)
                )
            )

            val result = initial.withCondition(
                FilterCondition.ModifiedDate(FilterComparator.LT, thirtyDaysAgo)
            )

            result.conditions shouldHaveSize 1
            result.dateConditions.first().comparator shouldBe FilterComparator.LT
            result.dateConditions.first().instant shouldBe thirtyDaysAgo
        }

        @Test
        fun `invalid range - GT newer than LT replaces LT`() {
            // "before 30 days ago" exists, adding "after 7 days ago" would create invalid range
            val initial = SearchFilter(
                listOf(
                    FilterCondition.ModifiedDate(FilterComparator.LT, thirtyDaysAgo)
                )
            )

            val result = initial.withCondition(
                FilterCondition.ModifiedDate(FilterComparator.GT, sevenDaysAgo)
            )

            result.conditions shouldHaveSize 1
            result.dateConditions.first().comparator shouldBe FilterComparator.GT
            result.dateConditions.first().instant shouldBe sevenDaysAgo
        }
    }

    @Nested
    inner class TypeConditionTests {

        @Test
        fun `adding Type replaces existing Type - only one allowed`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Type(FileType.FILE)
                )
            )

            val result = initial.withCondition(
                FilterCondition.Type(FileType.DIRECTORY)
            )

            result.conditions shouldHaveSize 1
            result.typeConditions.first().fileType shouldBe FileType.DIRECTORY
        }

        @Test
        fun `Type does not affect other condition types`() {
            val initial = SearchFilter(
                listOf(
                    FilterCondition.Size(FilterComparator.GTE, 1_000_000),
                    FilterCondition.ModifiedDate(FilterComparator.GT, Instant.DISTANT_PAST),
                )
            )

            val result = initial.withCondition(
                FilterCondition.Type(FileType.FILE)
            )

            result.conditions shouldHaveSize 3
            result.sizeConditions shouldHaveSize 1
            result.dateConditions shouldHaveSize 1
            result.typeConditions shouldHaveSize 1
        }
    }

    @Nested
    inner class ComparatorExtensionTests {

        @Test
        fun `GT is lower bound`() {
            FilterComparator.GT.isLowerBound shouldBe true
            FilterComparator.GT.isUpperBound shouldBe false
        }

        @Test
        fun `GTE is lower bound`() {
            FilterComparator.GTE.isLowerBound shouldBe true
            FilterComparator.GTE.isUpperBound shouldBe false
        }

        @Test
        fun `LT is upper bound`() {
            FilterComparator.LT.isLowerBound shouldBe false
            FilterComparator.LT.isUpperBound shouldBe true
        }

        @Test
        fun `LTE is upper bound`() {
            FilterComparator.LTE.isLowerBound shouldBe false
            FilterComparator.LTE.isUpperBound shouldBe true
        }

        @Test
        fun `EQ is exact`() {
            FilterComparator.EQ.isExact shouldBe true
            FilterComparator.EQ.isLowerBound shouldBe false
            FilterComparator.EQ.isUpperBound shouldBe false
        }
    }
}
