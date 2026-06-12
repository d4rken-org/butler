package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SearchEngineTest : BaseTest() {

    @Nested
    inner class ResultTests {

        @Test
        fun `InvalidQuery result type exists`() {
            val result: SearchEngine.Result = SearchEngine.Result.InvalidQuery
            result.shouldBeInstanceOf<SearchEngine.Result.InvalidQuery>()
        }

        @Test
        fun `NoTargets result type exists`() {
            val result: SearchEngine.Result = SearchEngine.Result.NoTargets
            result.shouldBeInstanceOf<SearchEngine.Result.NoTargets>()
        }

        @Test
        fun `PermissionsRequired result holds requirements`() {
            val requirements = PathRequirements(
                combos = setOf(setOf(SetupModule.Type.STORAGE)),
                complete = emptySet(),
            )
            val result = SearchEngine.Result.PermissionsRequired(requirements)

            result.requirements shouldBe requirements
            result.requirements.needsSetup shouldBe true
        }

        @Test
        fun `Error result holds exception`() {
            val exception = RuntimeException("Test error")
            val result = SearchEngine.Result.Error(exception)

            result.exception shouldBe exception
            result.exception.message shouldBe "Test error"
        }
    }

    @Nested
    inner class SearchProgressTests {

        @Test
        fun `SearchProgress holds progress data`() {
            val path = LocalPath.build("/sdcard")
            val progress = SearchEngine.SearchProgress(
                currentPath = path,
                itemsScanned = 100,
                resultsFound = 5,
            )

            progress.currentPath shouldBe path
            progress.itemsScanned shouldBe 100
            progress.resultsFound shouldBe 5
        }

        @Test
        fun `SearchProgress can start with zero counts`() {
            val path = LocalPath.build("/sdcard")
            val progress = SearchEngine.SearchProgress(
                currentPath = path,
                itemsScanned = 0,
                resultsFound = 0,
            )

            progress.itemsScanned shouldBe 0
            progress.resultsFound shouldBe 0
        }
    }

    @Nested
    inner class SearchTargetProgressTests {

        @Test
        fun `SearchTargetProgress Status enum has expected values`() {
            val statuses = SearchEngine.SearchTargetProgress.Status.entries

            statuses.size shouldBe 4
            statuses.map { it.name } shouldBe listOf("SEARCHING", "COMPLETED", "ERROR", "CANCELLED")
        }

        @Test
        fun `SearchTargetProgress with SEARCHING status`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 50,
                resultsFound = 2,
                status = SearchEngine.SearchTargetProgress.Status.SEARCHING,
            )

            progress.status shouldBe SearchEngine.SearchTargetProgress.Status.SEARCHING
            progress.exception shouldBe null
        }

        @Test
        fun `SearchTargetProgress with ERROR status holds exception`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val exception = RuntimeException("Access denied")
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 10,
                resultsFound = 0,
                status = SearchEngine.SearchTargetProgress.Status.ERROR,
                exception = exception,
            )

            progress.status shouldBe SearchEngine.SearchTargetProgress.Status.ERROR
            progress.exception shouldBe exception
        }

        @Test
        fun `SearchTargetProgress with COMPLETED status`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 1000,
                resultsFound = 25,
                status = SearchEngine.SearchTargetProgress.Status.COMPLETED,
            )

            progress.status shouldBe SearchEngine.SearchTargetProgress.Status.COMPLETED
            progress.itemsScanned shouldBe 1000
            progress.resultsFound shouldBe 25
        }

        @Test
        fun `SearchTargetProgress with CANCELLED status`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 500,
                resultsFound = 10,
                status = SearchEngine.SearchTargetProgress.Status.CANCELLED,
            )

            progress.status shouldBe SearchEngine.SearchTargetProgress.Status.CANCELLED
        }
    }
}
