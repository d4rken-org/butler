package eu.darken.butler.searcher.ui.search

import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SearcherQueryAssemblyTest : BaseTest() {

    @Test
    fun `content pattern applies while the toggle is enabled`() {
        val (filename, content) = SearcherWorkspaceViewModel.buildQueries(
            filenameText = "*.kt",
            contentText = "needle",
            contentSearchEnabled = true,
            filenameOptions = FilenameQuery(useRegex = true),
            contentOptions = ContentQuery(caseSensitive = true, wholeWord = true),
        )

        filename.pattern shouldBe "*.kt"
        filename.useRegex shouldBe true
        content.pattern shouldBe "needle"
        content.caseSensitive shouldBe true
        content.wholeWord shouldBe true
    }

    @Test
    fun `content pattern is dropped while the toggle is disabled`() {
        val (filename, content) = SearcherWorkspaceViewModel.buildQueries(
            filenameText = "*.kt",
            contentText = "hidden pattern",
            contentSearchEnabled = false,
            filenameOptions = FilenameQuery(),
            contentOptions = ContentQuery(),
        )

        filename.pattern shouldBe "*.kt"
        content.pattern shouldBe ""
        content.isNotEmpty shouldBe false
    }

    @Test
    fun `content options are preserved even when the pattern is dropped`() {
        val (_, content) = SearcherWorkspaceViewModel.buildQueries(
            filenameText = "",
            contentText = "hidden",
            contentSearchEnabled = false,
            filenameOptions = FilenameQuery(),
            contentOptions = ContentQuery(caseSensitive = true, useRegex = true),
        )

        // Options survive the toggle so re-enabling restores the previous behavior
        content.caseSensitive shouldBe true
        content.useRegex shouldBe true
    }

    @Test
    fun `blank content stays blank regardless of toggle`() {
        val (_, enabled) = SearcherWorkspaceViewModel.buildQueries(
            filenameText = "a",
            contentText = "",
            contentSearchEnabled = true,
            filenameOptions = FilenameQuery(),
            contentOptions = ContentQuery(),
        )
        enabled.pattern shouldBe ""
        enabled.isNotEmpty shouldBe false
    }
}
