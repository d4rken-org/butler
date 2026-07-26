package eu.darken.butler.searcher.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.searcher.R
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.core.WorkspaceDisplay

/** Search locations listed in a tab subtitle before the rest collapses into a "+N" suffix. */
private const val MAX_SUBTITLE_TARGETS = 3

/**
 * Tab identity of a Searcher workspace derived from its arguments alone: the query it will run,
 * described by where it will look. Pure and synchronous — used for both the dormant stand-in and
 * the live workspace's [eu.darken.butler.workspace.core.initialInfo] seed.
 */
fun deriveSearcherDisplay(arguments: SearcherArguments): WorkspaceDisplay? {
    val args = arguments as? SearcherArguments.Default ?: return null
    return searcherDisplay(
        filenameQuery = args.filenameQuery,
        contentQuery = args.contentQuery,
        targets = args.startTargets.orEmpty(),
    )
}

/**
 * The one identity derivation: the dormant stand-in feeds it the persisted arguments, the live
 * workspace feeds it the current query and targets, so the two can never describe the tab
 * differently. Null when neither a query nor a target says anything about this tab.
 */
fun searcherDisplay(
    filenameQuery: FilenameQuery?,
    contentQuery: ContentQuery?,
    targets: List<SearchTarget>,
): WorkspaceDisplay? {
    val title = searcherQueryTitle(filenameQuery, contentQuery)
    val subtitle = searcherTargetsSubtitle(targets)
    if (title == null && subtitle == null) return null
    return WorkspaceDisplay(title = title, subtitle = subtitle)
}

/** Filename pattern, else content pattern; null when neither carries a query yet. */
fun searcherQueryTitle(filenameQuery: FilenameQuery?, contentQuery: ContentQuery?): CaString? = when {
    filenameQuery?.isNotEmpty == true -> filenameQuery.pattern.toCaString()
    contentQuery?.isNotEmpty == true -> contentQuery.pattern.toCaString()
    else -> null
}

/**
 * Enabled search targets as a single line. Resolution happens inside the [CaString]: a target's
 * own display text is context aware, and a large target set collapses so the line stays bounded.
 */
fun searcherTargetsSubtitle(targets: List<SearchTarget>): CaString? {
    val enabled = targets.filter { it.enabled }
    if (enabled.isEmpty()) return null
    return caString { ctx ->
        // Resolved lazily, so blank labels can only be dropped here
        val texts = enabled.mapNotNull { target -> target.displayText.get(ctx).takeIf { it.isNotBlank() } }
        val shown = texts.take(MAX_SUBTITLE_TARGETS).joinToString(", ")
        val overflow = texts.size - MAX_SUBTITLE_TARGETS
        when {
            overflow > 0 -> ctx.getString(R.string.searcher_workspace_targets_overflow, shown, overflow)
            else -> shown
        }
    }
}
