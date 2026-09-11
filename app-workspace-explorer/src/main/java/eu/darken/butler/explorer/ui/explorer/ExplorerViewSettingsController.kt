package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.ExplorerTabViewStore
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.core.PatternMatcher
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.sorting.rules.EffectiveSortResolution
import eu.darken.butler.explorer.core.sorting.rules.EffectiveSortResolver
import eu.darken.butler.explorer.core.sorting.rules.ExplorerTabSortStore
import eu.darken.butler.explorer.core.sorting.rules.FolderSortRulesRepo
import eu.darken.butler.explorer.core.sorting.rules.SortRuleCandidate
import eu.darken.butler.explorer.core.sorting.rules.TabSortOverrides
import eu.darken.butler.explorer.core.sorting.rules.sortAncestorKeys
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json

/**
 * View style, sort resolution and name/type filtering for the item listing.
 * Owns the reactive inputs of the ViewModel's processed-items pipeline.
 */
class ExplorerViewSettingsController(
    private val explorerSettings: ExplorerSettings,
    private val folderSortRules: FolderSortRulesRepo,
    private val tabSortStore: ExplorerTabSortStore,
    private val tabViewStore: ExplorerTabViewStore,
    private val json: Json,
    private val workspaceId: Workspace.Id,
    currentLocation: Flow<ExplorerLocation?>,
    scope: CoroutineScope,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
) {

    private val viewStyleSeed: ExplorerViewStyle =
        tabViewStore.currentViewStyle(workspaceId) ?: explorerSettings.defaultViewStyle.valueBlocking

    /**
     * Derived from the slot rather than mirroring it, so a write another tab's sheet made to this
     * tab's slot reaches this page too. The seed keeps the first composed frame correct.
     */
    val viewStyle: StateFlow<ExplorerViewStyle> = tabViewStore
        .observeViewStyle(workspaceId)
        .map { it ?: viewStyleSeed }
        .stateIn(scope, SharingStarted.Eagerly, viewStyleSeed)

    private val filterStateFlow = MutableStateFlow(tabViewStore.currentFilter(workspaceId))
    val filterState: StateFlow<FilterState> = filterStateFlow

    init {
        // Materializes the style at tab creation, so a later change of the global default cannot
        // retroactively restyle a tab the user already has open.
        tabViewStore.ensureViewStyle(workspaceId, viewStyleSeed)
    }

    /**
     * [locationKey] is the [ExplorerLocation.locationId] the resolution was computed for.
     *
     * It has to travel with the resolution because `flatMapLatest` does not clear the last value a
     * `combine` downstream of it holds: without the key, folder B's items would briefly render under
     * folder A's sort while navigating.
     */
    data class ResolvedSort(
        val locationKey: String?,
        val resolution: EffectiveSortResolution,
    )

    /** Eager, because the sort sheet reads this by value rather than collecting it. */
    val tabOverrides: StateFlow<TabSortOverrides> = tabSortStore
        .overridesFor(workspaceId)
        .stateIn(scope, SharingStarted.Eagerly, TabSortOverrides())

    /**
     * The sort the current location actually gets, shared rather than cold: two collectors on a cold
     * flow would open two independent rule observers that can race.
     *
     * Starts as null - "not resolved yet" - and the listing waits for it, so a rule lookup failure
     * has to surface as a fallback resolution rather than as silence, or the listing would never
     * render at all.
     */
    val resolvedSort: StateFlow<ResolvedSort?> = currentLocation
        .distinctUntilChanged { old, new -> old?.locationId == new?.locationId }
        .flatMapLatest { location -> resolutionsFor(location) }
        .stateIn(scope, SharingStarted.Lazily, null)

    private fun resolutionsFor(location: ExplorerLocation?): Flow<ResolvedSort> {
        val locationKey = location?.locationId
        val path = (location as? ExplorerLocation.Directory)?.path
        // Home, Device and Trash have no path, so there is nothing to hang a rule on
            ?: return combine(
                explorerSettings.sortSettings.flow,
                tabSortStore.overridesFor(workspaceId),
            ) { globalDefault, tab ->
                ResolvedSort(locationKey, EffectiveSortResolution(settings = tab.default ?: globalDefault))
            }

        val savedRules = folderSortRules.observeRulesFor(path)
            .catch { e ->
                log(TAG, ERROR) { "Folder sort rule lookup failed for $path: ${e.asLog()}" }
                emit(emptyList())
            }

        return combine(
            explorerSettings.sortSettings.flow,
            tabSortStore.overridesFor(workspaceId),
            savedRules,
        ) { globalDefault, tab, saved ->
            ResolvedSort(
                locationKey = locationKey,
                resolution = EffectiveSortResolver.resolve(
                    ancestorKeys = path.sortAncestorKeys(),
                    tabRules = tab.rules.mapValues { (_, rule) ->
                        SortRuleCandidate(
                            settings = rule.settings,
                            subtree = rule.subtree,
                            path = decodePath(rule.path),
                        )
                    },
                    savedRules = saved.associate { rule ->
                        rule.pathKey to SortRuleCandidate(
                            settings = rule.settings,
                            subtree = rule.subtree,
                            path = rule.path,
                        )
                    },
                    tabDefault = tab.default,
                    globalDefault = globalDefault,
                ),
            )
        }
    }

    /** A tab rule whose path cannot be read still applies; it just cannot name itself in a notice. */
    private fun decodePath(serialized: String): APath<*>? = try {
        json.decodeFromString(PolymorphicSerializer(APath::class), serialized)
    } catch (e: Exception) {
        log(TAG, WARN) { "Tab sort rule has an unreadable path: ${e.asLog()}" }
        null
    }

    /** The live path: every control change in the view options sheet lands here. */
    fun applyToTab(style: ExplorerViewStyle) {
        tabViewStore.setViewStyle(workspaceId, style)
    }

    /**
     * Only the setting. The tab already carries [style] through [applyToTab], and sequencing a slot
     * write after the suspending persist could reapply it over a newer choice.
     */
    fun setAsDefault(style: ExplorerViewStyle) {
        doLaunch {
            explorerSettings.defaultViewStyle.value(style)
        }
    }

    fun applyFilterState(state: FilterState) {
        filterStateFlow.value = state
        tabViewStore.setFilter(workspaceId, state)
    }

    fun resetFilters() {
        filterStateFlow.value = FilterState()
        tabViewStore.setFilter(workspaceId, FilterState())
    }

    fun applyFilters(
        items: List<ExplorerItem>,
        filterState: FilterState,
        useRegexPatterns: Boolean,
        showHidden: Boolean,
    ): List<ExplorerItem> = items.filter(itemFilter(filterState, useRegexPatterns, showHidden))

    /** Whether a combination of filters and [showHidden] would leave anything on screen. */
    fun showsAnythingWhen(
        items: List<ExplorerItem>,
        filterState: FilterState,
        useRegexPatterns: Boolean,
        showHidden: Boolean,
    ): Boolean = items.any(itemFilter(filterState, useRegexPatterns, showHidden))

    /**
     * Dot-prefixed entries in the unfiltered listing, directories included.
     *
     * An inventory fact about the folder, not a promise that revealing them adds exactly this many
     * rows: the name and type filters still apply to whatever is revealed.
     */
    fun countHiddenEntries(items: List<ExplorerItem>): Int = items.count { it.isHiddenEntry }

    private fun itemFilter(
        filterState: FilterState,
        useRegexPatterns: Boolean,
        showHidden: Boolean,
    ): (ExplorerItem) -> Boolean {
        // Compiled once per pass rather than per item; a pattern that does not compile matches nothing
        val hasExclude = filterState.excludePattern.isNotBlank()
        val excludeRegex = if (hasExclude) PatternMatcher.compile(filterState.excludePattern, useRegexPatterns) else null
        val hasInclude = filterState.includePattern.isNotBlank()
        val includeRegex = if (hasInclude) PatternMatcher.compile(filterState.includePattern, useRegexPatterns) else null

        return filter@{ item ->
            // Before the pattern and type checks, so an entry both of them would drop is dropped once.
            if (!showHidden && item.isHiddenEntry) return@filter false

            val itemName = when (item) {
                is ExplorerItem.Path -> item.path.name
                is ExplorerItem.Trash.Root -> item.originalLookup.name
                is ExplorerItem.Trash.Nested -> item.lookup.name
                else -> return@filter true // Keep non-path items (like peek items)
            }

            // Apply exclude pattern first
            if (hasExclude && PatternMatcher.matches(itemName, excludeRegex)) {
                return@filter false
            }

            // Apply include pattern
            if (hasInclude && !PatternMatcher.matches(itemName, includeRegex)) {
                return@filter false
            }

            // Apply file type filter
            when (filterState.fileTypeFilter) {
                FileTypeFilter.FILES_ONLY -> {
                    if (item is ExplorerItem.Directory) return@filter false
                    if (item is ExplorerItem.Trash.Root && item.originalLookup.fileType == FileType.DIRECTORY) return@filter false
                    if (item is ExplorerItem.Trash.Nested && item.isDirectory) return@filter false
                }
                FileTypeFilter.FOLDERS_ONLY -> {
                    if (item is ExplorerItem.File) return@filter false
                    if (item is ExplorerItem.Trash.Root && item.originalLookup.fileType == FileType.FILE) return@filter false
                    if (item is ExplorerItem.Trash.Nested && item.isFile) return@filter false
                }
                FileTypeFilter.ALL -> {} // No filtering needed
            }

            true
        }
    }

    /**
     * A dot-prefixed name on a path entry. Trashed entries are deliberately not covered: restoring
     * acts on what the listing offers, so an entry the dot rule removes could not be restored.
     */
    private val ExplorerItem.isHiddenEntry: Boolean
        get() = this is ExplorerItem.Path && path.name.startsWith(".")

    companion object {
        private val TAG = logTag("Explorer", "ViewSettings")
    }
}
