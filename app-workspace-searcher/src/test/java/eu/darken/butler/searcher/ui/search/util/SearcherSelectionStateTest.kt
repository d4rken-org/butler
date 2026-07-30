package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.resultKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SearcherSelectionStateTest : BaseTest() {

    private fun file(name: String) = SearchItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Documents", name),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
        ),
        matchedQuery = "",
    )

    private val first = file("a.txt")
    private val second = file("b.txt")

    @Test
    fun `entering selection mode selects the pressed result`() {
        val state = SearcherSelectionState(selectableResults = listOf(first, second))

        state.enterSelectionMode(first).selectedResultIds shouldBe setOf(first.resultKey)
    }

    @Test
    fun `entering selection mode is a no-op once something is selected`() {
        // The long press is the drag gesture from here on, taps do the selecting.
        val state = SearcherSelectionState(
            selectableResults = listOf(first, second),
            selectedResultIds = setOf(first.resultKey),
        )

        state.enterSelectionMode(second) shouldBe state
    }
}
