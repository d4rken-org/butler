package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.engine.ContentMatcher
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.FilterComparator
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchFilter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SearchItemMatcherTest : BaseTest() {

    private val contentMatcher = mockk<ContentMatcher>()
    private val matcher = SearchItemMatcher(contentMatcher)
    private val recordedErrors = mutableListOf<APath<*>?>()
    private fun recordError(path: APath<*>?) {
        recordedErrors += path
    }

    private fun lookup(name: String = "needle.txt", fileType: FileType = FileType.FILE) = LocalPathLookup(
        lookedUp = LocalPath.build("/sdcard/$name"),
        fileType = fileType,
        size = 10L,
        modifiedAt = null,
    )

    private fun query(
        filename: String? = null,
        content: String? = null,
        filter: SearchFilter = SearchFilter(),
    ) = SearchQuery(
        filenameQuery = filename?.let { FilenameQuery(pattern = it) } ?: FilenameQuery(),
        contentQuery = content?.let { ContentQuery(pattern = it) } ?: ContentQuery(),
        targets = emptyList(),
        filter = filter,
    )

    private fun contentOutcome(outcome: ContentMatcher.Outcome) {
        coEvery { contentMatcher.matchesContent(any(), any(), any()) } returns outcome
    }

    @Test
    fun `filename-only match`() = runTest {
        val context = matcher.match(lookup(), query(filename = "needle"), false, ::recordError)
        context!!.matchType shouldBe SearchItem.MatchContext.MatchType.FILENAME
        recordedErrors shouldBe emptyList()
    }

    @Test
    fun `filename-only no match`() = runTest {
        matcher.match(lookup("other.txt"), query(filename = "needle"), false, ::recordError) shouldBe null
    }

    @Test
    fun `content-only match on a file`() = runTest {
        contentOutcome(
            ContentMatcher.Outcome.Match(
                context = SearchItem.MatchContext(matchType = SearchItem.MatchContext.MatchType.CONTENT),
                degraded = false,
            )
        )
        val context = matcher.match(lookup(), query(content = "hello"), false, ::recordError)
        context!!.matchType shouldBe SearchItem.MatchContext.MatchType.CONTENT
    }

    @Test
    fun `content-only never matches directories`() = runTest {
        matcher.match(
            lookup(fileType = FileType.DIRECTORY),
            query(content = "hello"),
            false,
            ::recordError,
        ) shouldBe null
    }

    @Test
    fun `AND semantics require both filename and content`() = runTest {
        contentOutcome(
            ContentMatcher.Outcome.Match(
                context = SearchItem.MatchContext(matchType = SearchItem.MatchContext.MatchType.CONTENT),
                degraded = false,
            )
        )
        val both = matcher.match(lookup(), query(filename = "needle", content = "x"), false, ::recordError)
        both!!.matchType shouldBe SearchItem.MatchContext.MatchType.BOTH

        // Filename gate fails: content is never consulted
        matcher.match(lookup("other.txt"), query(filename = "needle", content = "x"), false, ::recordError) shouldBe null
    }

    @Test
    fun `filter-only queries match everything that passed the filters`() = runTest {
        val filter = SearchFilter(conditions = listOf(FilterCondition.Size(FilterComparator.GT, 1L)))
        val context = matcher.match(lookup(), query(filter = filter), false, ::recordError)
        context!!.matchType shouldBe SearchItem.MatchContext.MatchType.FILTER
    }

    @Test
    fun `no patterns and no filters matches nothing`() = runTest {
        matcher.match(lookup(), query(), false, ::recordError) shouldBe null
    }

    @Test
    fun `skipped binary content is no match and no error`() = runTest {
        contentOutcome(ContentMatcher.Outcome.Skipped(ContentMatcher.Outcome.Skipped.Reason.BINARY))
        matcher.match(lookup(), query(content = "x"), false, ::recordError) shouldBe null
        recordedErrors shouldBe emptyList()
    }

    @Test
    fun `failed content read records an error and yields no match`() = runTest {
        contentOutcome(ContentMatcher.Outcome.Failed(error = IOException("boom")))
        matcher.match(lookup(), query(content = "x"), false, ::recordError) shouldBe null
        recordedErrors.size shouldBe 1
    }

    @Test
    fun `degraded match records an error but still matches`() = runTest {
        contentOutcome(
            ContentMatcher.Outcome.Match(
                context = SearchItem.MatchContext(matchType = SearchItem.MatchContext.MatchType.CONTENT),
                degraded = true,
            )
        )
        matcher.match(lookup(), query(content = "x"), false, ::recordError)!!.matchType shouldBe
            SearchItem.MatchContext.MatchType.CONTENT
        recordedErrors.size shouldBe 1
    }

    @Test
    fun `degraded no-match records an error`() = runTest {
        contentOutcome(ContentMatcher.Outcome.NoMatch(degraded = true))
        matcher.match(lookup(), query(content = "x"), false, ::recordError) shouldBe null
        recordedErrors.size shouldBe 1
    }

    @Test
    fun `matchedQueryFor maps match types to patterns`() {
        val q = query(filename = "name-pat", content = "content-pat")
        matcher.matchedQueryFor(SearchItem.MatchContext.MatchType.FILENAME, q) shouldBe "name-pat"
        matcher.matchedQueryFor(SearchItem.MatchContext.MatchType.CONTENT, q) shouldBe "content-pat"
        matcher.matchedQueryFor(SearchItem.MatchContext.MatchType.BOTH, q) shouldBe "name-pat"
        matcher.matchedQueryFor(SearchItem.MatchContext.MatchType.FILTER, q) shouldBe ""
    }
}
