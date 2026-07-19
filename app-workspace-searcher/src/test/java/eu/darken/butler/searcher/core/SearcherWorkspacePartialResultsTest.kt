package eu.darken.butler.searcher.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SearcherWorkspacePartialResultsTest : BaseTest() {

    private fun targetProgress(
        status: SearchEngine.SearchTargetProgress.Status,
        errorCount: Int = 0,
        path: String = "/sdcard",
    ) = SearchEngine.SearchTargetProgress(
        target = SearchTarget.Path.from(LocalPath.build(path)),
        itemsScanned = 100,
        resultsFound = 1,
        status = status,
        errorCount = errorCount,
    )

    private fun item(path: String): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
            target = null,
        ),
        matchedQuery = "",
    )

    @Test
    fun `results plus a target with errors is partial`() {
        val state = SearcherWorkspace.State(
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            results = listOf(item("/sdcard/found.txt")),
            targetProgress = listOf(
                targetProgress(SearchEngine.SearchTargetProgress.Status.COMPLETED, errorCount = 3),
            ),
        )

        state.partialResults shouldBe true
    }

    @Test
    fun `zero results with errors is still partial`() {
        // "nothing found" and "nothing found but locations were unsearchable" must not look alike
        val state = SearcherWorkspace.State(
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            results = emptyList(),
            targetProgress = listOf(
                targetProgress(SearchEngine.SearchTargetProgress.Status.COMPLETED, errorCount = 1),
            ),
        )

        state.partialResults shouldBe true
    }

    @Test
    fun `failed target status without item errors is partial`() {
        val state = SearcherWorkspace.State(
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            targetProgress = listOf(
                targetProgress(SearchEngine.SearchTargetProgress.Status.COMPLETED, path = "/sdcard"),
                targetProgress(SearchEngine.SearchTargetProgress.Status.ERROR, path = "/data"),
            ),
        )

        state.partialResults shouldBe true
    }

    @Test
    fun `overall error status is not partial`() {
        // A fully failed search is reported as an error, not as partial results
        val state = SearcherWorkspace.State(
            searchStatus = SearcherWorkspace.State.SearchStatus.ERROR,
            targetProgress = listOf(
                targetProgress(SearchEngine.SearchTargetProgress.Status.ERROR, errorCount = 2),
            ),
        )

        state.partialResults shouldBe false
    }

    @Test
    fun `no errors is not partial`() {
        val state = SearcherWorkspace.State(
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            results = listOf(item("/sdcard/found.txt")),
            targetProgress = listOf(
                targetProgress(SearchEngine.SearchTargetProgress.Status.COMPLETED),
                targetProgress(SearchEngine.SearchTargetProgress.Status.COMPLETED, path = "/data"),
            ),
        )

        state.partialResults shouldBe false
    }

    @Test
    fun `cap-induced cancelled targets are not partial`() {
        // Hitting the result cap cancels remaining scans; that is reported via limitReached
        val state = SearcherWorkspace.State(
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            limitReached = true,
            targetProgress = listOf(
                targetProgress(SearchEngine.SearchTargetProgress.Status.COMPLETED),
                targetProgress(SearchEngine.SearchTargetProgress.Status.CANCELLED, path = "/data"),
            ),
        )

        state.partialResults shouldBe false
    }

    @Test
    fun `empty target progress is not partial`() {
        SearcherWorkspace.State().partialResults shouldBe false
    }
}
