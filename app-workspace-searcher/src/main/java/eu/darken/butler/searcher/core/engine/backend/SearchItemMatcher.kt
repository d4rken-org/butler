package eu.darken.butler.searcher.core.engine.backend

import dagger.Reusable
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.engine.ContentMatcher
import eu.darken.butler.searcher.core.engine.PatternMatcher
import eu.darken.butler.searcher.core.engine.patternOptions
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import javax.inject.Inject

/**
 * Filename/content/filter match semantics shared by all backends. Failed or degraded content
 * reads are reported through [recordError] so they feed the caller's partial-results accounting.
 */
@Reusable
class SearchItemMatcher @Inject constructor(
    private val contentMatcher: ContentMatcher,
) {

    suspend fun match(
        lookup: APathLookup<*>,
        searchQuery: SearchQuery,
        includeBinaries: Boolean,
        recordError: (APath<*>?) -> Unit,
    ): SearchItem.MatchContext? {
        val filenameQuery = searchQuery.filenameQuery
        val contentQuery = searchQuery.contentQuery
        val hasFilenameQuery = filenameQuery.isNotEmpty
        val hasContentQuery = contentQuery.isNotEmpty

        suspend fun contentContext(): SearchItem.MatchContext? =
            when (val outcome = contentMatcher.matchesContent(lookup, contentQuery, includeBinaries)) {
                is ContentMatcher.Outcome.Match -> {
                    if (outcome.degraded) recordError(lookup.lookedUp)
                    outcome.context
                }
                is ContentMatcher.Outcome.NoMatch -> {
                    if (outcome.degraded) recordError(lookup.lookedUp)
                    null
                }
                is ContentMatcher.Outcome.Skipped -> null
                is ContentMatcher.Outcome.Failed -> {
                    recordError(lookup.lookedUp)
                    null
                }
            }

        return when {
            // Case 1: Only filename pattern - match filename only
            hasFilenameQuery && !hasContentQuery -> {
                if (matchesFilename(lookup.name, filenameQuery)) {
                    SearchItem.MatchContext(matchType = SearchItem.MatchContext.MatchType.FILENAME)
                } else {
                    null
                }
            }

            // Case 2: Only content pattern - match content only (for files)
            !hasFilenameQuery && hasContentQuery -> {
                if (lookup.fileType != FileType.FILE) return null
                contentContext()
            }

            // Case 3: Both patterns - filename must match first (AND logic)
            hasFilenameQuery && hasContentQuery -> {
                if (!matchesFilename(lookup.name, filenameQuery)) return null
                if (lookup.fileType != FileType.FILE) return null
                contentContext()?.copy(matchType = SearchItem.MatchContext.MatchType.BOTH)
            }

            // Case 4: Filter-only - no patterns but filters exist (already applied upstream)
            searchQuery.filter.hasConditions() ->
                SearchItem.MatchContext(matchType = SearchItem.MatchContext.MatchType.FILTER)

            // Case 5: No patterns and no filters - shouldn't happen (validated upstream)
            else -> null
        }
    }

    fun matchesFilename(name: String, filenameQuery: FilenameQuery): Boolean =
        PatternMatcher.matches(name, filenameQuery.pattern, filenameQuery.patternOptions).isFound

    fun matchedQueryFor(matchType: SearchItem.MatchContext.MatchType, query: SearchQuery): String = when (matchType) {
        SearchItem.MatchContext.MatchType.FILENAME -> query.filenameQuery.pattern
        SearchItem.MatchContext.MatchType.CONTENT -> query.contentQuery.pattern
        SearchItem.MatchContext.MatchType.BOTH -> query.filenameQuery.pattern
        SearchItem.MatchContext.MatchType.FILTER -> ""
    }
}
