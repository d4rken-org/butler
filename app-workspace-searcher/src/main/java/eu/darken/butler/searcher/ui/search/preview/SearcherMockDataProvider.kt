package eu.darken.butler.searcher.ui.search.preview

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.SearcherSelectionState
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Mock data provider for Searcher compose previews.
 *
 * Provides factory methods to create SearchResult instances for preview functions.
 * All mock data uses LocalPath/LocalPathLookup for simplicity.
 */
object SearcherMockDataProvider {

    private object MockTimes {
        fun hoursAgo(hours: Long): Instant = Clock.System.now() - hours.hours
        fun secondsAgo(seconds: Long): Instant = Clock.System.now() - seconds.seconds
    }

    private object MockSizes {
        const val KB = 1024L
        const val MB = KB * 1024

        fun kb(value: Long) = value * KB
        fun mb(value: Long) = value * MB
    }

    /**
     * Create a mock SearchResult with customizable properties.
     */
    fun createMockSearchResult(
        name: String = "example.txt",
        path: String = "/storage/emulated/0/Documents/$name",
        fileType: FileType = FileType.FILE,
        sizeKB: Long? = 1,
        hoursAgo: Long = 1,
        matchedQuery: String = "",
        matchContext: SearchItem.MatchContext? = null
    ): SearchItem {
        val lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = fileType,
            size = sizeKB?.let { MockSizes.kb(it) },
            modifiedAt = if (hoursAgo > 0) MockTimes.hoursAgo(hoursAgo) else null,
            target = null,
        )

        return SearchItem.fromLookup(
            lookup = lookup,
            matchedQuery = matchedQuery,
            matchContext = matchContext
        )
    }

    /**
     * Create a mock text file for previews.
     */
    fun createMockTextFile(
        name: String = "document.txt",
        sizeKB: Long = 5,
        hoursAgo: Long = 2
    ): SearchItem = createMockSearchResult(
        name = name,
        path = "/storage/emulated/0/Documents/$name",
        fileType = FileType.FILE,
        sizeKB = sizeKB,
        hoursAgo = hoursAgo
    )

    /**
     * Create a mock directory for previews.
     */
    fun createMockDirectory(
        name: String = "Pictures",
        path: String = "/storage/emulated/0/$name",
        hoursAgo: Long = 24
    ): SearchItem = createMockSearchResult(
        name = name,
        path = path,
        fileType = FileType.DIRECTORY,
        sizeKB = null,
        hoursAgo = hoursAgo
    )

    /**
     * Create mock search history items for previews.
     */
    fun createMockSearchHistory(): List<SearchHistory.SearchHistoryItem> = listOf(
        SearchHistory.SearchHistoryItem(
            id = "history-1",
            baseQuery = "config.json",
            searchQuery = SearchQuery.create(
                paths = listOf(LocalPath.build("/storage/emulated/0/Android")),
                filenameQuery = FilenameQuery(pattern = "config.json"),
            ),
            searchedAt = Clock.System.now() - 1.hours,
            resultCount = 5,
        ),
        SearchHistory.SearchHistoryItem(
            id = "history-2",
            baseQuery = "photos",
            searchQuery = SearchQuery.create(
                paths = listOf(LocalPath.build("/storage/emulated/0/DCIM")),
                filenameQuery = FilenameQuery(pattern = "photos", wholeWord = true),
            ),
            searchedAt = Clock.System.now() - 3.hours,
            resultCount = 0,
        ),
        SearchHistory.SearchHistoryItem(
            id = "history-3",
            baseQuery = "readme",
            searchQuery = SearchQuery.create(
                paths = listOf(LocalPath.build("/storage/emulated/0/Documents")),
                filenameQuery = FilenameQuery(pattern = "readme", caseSensitive = true),
            ),
            searchedAt = Clock.System.now() - 5.hours,
            resultCount = 12,
        )
    )

    /**
     * Create mock empty state for SearcherWorkspacePage previews.
     */
    fun createMockEmptyState(): SearcherWorkspaceViewModel.State.Ready =
        SearcherWorkspaceViewModel.State.Ready(
            searchTargets = listOf(
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0"))
            )
        )

    /**
     * Create mock state with search history for SearcherWorkspacePage previews.
     */
    fun createMockHistoryState(): SearcherWorkspaceViewModel.State.Ready =
        SearcherWorkspaceViewModel.State.Ready(
            searchTargets = listOf(
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0"))
            ),
            searchHistory = createMockSearchHistory()
        )

    /**
     * Create mock state with search results for SearcherWorkspacePage previews.
     * Shows selection mode with 3 out of 5 items selected.
     */
    fun createMockResultsState(): SearcherWorkspaceViewModel.State.Ready {
        val results = listOf(
            createMockSearchResult(
                name = "config.json",
                path = "/storage/emulated/0/Android/data/eu.darken.butler/config.json",
                fileType = FileType.FILE,
                sizeKB = 2,
                hoursAgo = 1
            ),
            createMockSearchResult(
                name = "app-config.xml",
                path = "/storage/emulated/0/Documents/projects/app-config.xml",
                fileType = FileType.FILE,
                sizeKB = 5,
                hoursAgo = 3
            ),
            createMockSearchResult(
                name = "config.properties",
                path = "/storage/emulated/0/Downloads/backup/config.properties",
                fileType = FileType.FILE,
                sizeKB = 1,
                hoursAgo = 12
            ),
            createMockSearchResult(
                name = "server-config.yaml",
                path = "/storage/emulated/0/Documents/config/server-config.yaml",
                fileType = FileType.FILE,
                sizeKB = 8,
                hoursAgo = 2
            ),
            createMockSearchResult(
                name = "config",
                path = "/storage/emulated/0/Android/data/com.example.app/config",
                fileType = FileType.DIRECTORY,
                sizeKB = null,
                hoursAgo = 24
            )
        )

        // Select 3 items: config.json, app-config.xml, and server-config.yaml
        val selectedResults = listOf(results[0], results[1], results[3])

        return SearcherWorkspaceViewModel.State.Ready(
            filenameQuery = "config",
            searchTargets = listOf(
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0"))
            ),
            workspaceState = SearcherWorkspace.State(
                searchStatus = SearcherWorkspace.State.SearchStatus.SEARCHING,
                results = results,
                targetProgress = listOf(
                    SearchEngine.SearchTargetProgress(
                        target = SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Android")),
                        itemsScanned = 1247,
                        resultsFound = 2,
                        status = SearchEngine.SearchTargetProgress.Status.COMPLETED
                    ),
                    SearchEngine.SearchTargetProgress(
                        target = SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                        itemsScanned = 583,
                        resultsFound = 2,
                        status = SearchEngine.SearchTargetProgress.Status.SEARCHING
                    ),
                    SearchEngine.SearchTargetProgress(
                        target = SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                        itemsScanned = 0,
                        resultsFound = 0,
                        status = SearchEngine.SearchTargetProgress.Status.SEARCHING
                    )
                ),
            ),
            selectionState = SearcherSelectionState(
                selectableResults = results,
                selectedResultIds = selectedResults.map { it.path.path }.toSet()
            ),
            availableActions = listOf(
                SearcherActionBarItem.Copy(selectedResults),
                SearcherActionBarItem.Cut(selectedResults),
                SearcherActionBarItem.Delete(selectedResults, trashEnabled = true),
                SearcherActionBarItem.Share(selectedResults),
                SearcherActionBarItem.SelectAll,
                SearcherActionBarItem.DeselectAll,
            ),
            trashEnabled = true,
        )
    }

    /**
     * Create mock searching state with multiple targets and varied progress for SearcherWorkspacePage previews.
     */
    fun createMockSearchingWithProgressState(): SearcherWorkspaceViewModel.State.Ready =
        SearcherWorkspaceViewModel.State.Ready(
            filenameQuery = "log",
            searchTargets = listOf(
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Android")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download"))
            ),
            workspaceState = SearcherWorkspace.State(
                searchStatus = SearcherWorkspace.State.SearchStatus.SEARCHING,
                results = listOf(
                    createMockSearchResult(
                        name = "app.log",
                        path = "/storage/emulated/0/Android/data/eu.darken.butler/app.log",
                        fileType = FileType.FILE,
                        sizeKB = 45,
                        hoursAgo = 1
                    ),
                    createMockSearchResult(
                        name = "error.log",
                        path = "/storage/emulated/0/Android/data/com.example.app/error.log",
                        fileType = FileType.FILE,
                        sizeKB = 12,
                        hoursAgo = 2
                    ),
                    createMockSearchResult(
                        name = "debug.log",
                        path = "/storage/emulated/0/Documents/logs/debug.log",
                        fileType = FileType.FILE,
                        sizeKB = 128,
                        hoursAgo = 1
                    ),
                    createMockSearchResult(
                        name = "system.log",
                        path = "/storage/emulated/0/Documents/logs/system.log",
                        fileType = FileType.FILE,
                        sizeKB = 256,
                        hoursAgo = 3
                    )
                ),
                targetProgress = listOf(
                    SearchEngine.SearchTargetProgress(
                        target = SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Android")),
                        itemsScanned = 1247,
                        resultsFound = 2,
                        status = SearchEngine.SearchTargetProgress.Status.COMPLETED
                    ),
                    SearchEngine.SearchTargetProgress(
                        target = SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                        itemsScanned = 583,
                        resultsFound = 2,
                        status = SearchEngine.SearchTargetProgress.Status.SEARCHING
                    ),
                    SearchEngine.SearchTargetProgress(
                        target = SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                        itemsScanned = 0,
                        resultsFound = 0,
                        status = SearchEngine.SearchTargetProgress.Status.SEARCHING
                    )
                )
            )
        )
}
