package eu.darken.butler.searcher.ui.search.preview

import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.core.FilenameQuery
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.ui.search.SearcherAction
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.workspace.core.Workspace
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
     * Create a mock PDF file for previews.
     */
    fun createMockPdfFile(
        name: String = "document.pdf",
        sizeMB: Long = 1,
        hoursAgo: Long = 1
    ): SearchItem = createMockSearchResult(
        name = name,
        path = "/storage/emulated/0/Downloads/$name",
        fileType = FileType.FILE,
        sizeKB = sizeMB * 1024,
        hoursAgo = hoursAgo
    )

    /**
     * Create a mock image file for previews.
     */
    fun createMockImageFile(
        name: String = "photo.jpg",
        sizeMB: Long = 2,
        hoursAgo: Long = 1,
        metadata: Map<String, String> = mapOf("Resolution" to "1920x1080")
    ): SearchItem = createMockSearchResult(
        name = name,
        path = "/storage/emulated/0/Pictures/$name",
        fileType = FileType.FILE,
        sizeKB = sizeMB * 1024,
        hoursAgo = hoursAgo
    )

    /**
     * Create a mock video file for previews.
     */
    fun createMockVideoFile(
        name: String = "video.mp4",
        sizeMB: Long = 25,
        hoursAgo: Long = 3,
        metadata: Map<String, String> = mapOf("Duration" to "2:34", "Quality" to "1080p")
    ): SearchItem = createMockSearchResult(
        name = name,
        path = "/storage/emulated/0/Movies/$name",
        fileType = FileType.FILE,
        sizeKB = sizeMB * 1024,
        hoursAgo = hoursAgo
    )

    /**
     * Create a mock audio file for previews.
     */
    fun createMockAudioFile(
        name: String = "song.mp3",
        sizeMB: Long = 4,
        hoursAgo: Long = 2,
        metadata: Map<String, String> = mapOf("Duration" to "3:45", "Artist" to "Unknown")
    ): SearchItem = createMockSearchResult(
        name = name,
        path = "/storage/emulated/0/Music/$name",
        fileType = FileType.FILE,
        sizeKB = sizeMB * 1024,
        hoursAgo = hoursAgo
    )

    /**
     * Create a mock APK file for previews.
     */
    fun createMockApkFile(
        name: String = "app.apk",
        sizeMB: Long = 35,
        hoursAgo: Long = 1,
        metadata: Map<String, String> = mapOf("Package" to "com.example.app", "Version" to "2.1.0")
    ): SearchItem = createMockSearchResult(
        name = name,
        path = "/storage/emulated/0/Download/$name",
        fileType = FileType.FILE,
        sizeKB = sizeMB * 1024,
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
     * Create a mock JSON config file for previews.
     */
    fun createMockConfigFile(
        name: String = "config.json",
        sizeBytes: Long = 256,
        secondsAgo: Long = 300
    ): SearchItem {
        val lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Android/data/eu.darken.butler/$name"),
            fileType = FileType.FILE,
            size = sizeBytes,
            modifiedAt = MockTimes.secondsAgo(secondsAgo),
            target = null,
        )

        return SearchItem.fromLookup(
            lookup = lookup,
            matchedQuery = "",
            matchContext = null
        )
    }

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
     * Create a list of mock search results for previews.
     */
    fun createMockSearchResults(): List<SearchItem> = listOf(
        createMockTextFile(name = "notes.txt", sizeKB = 15, hoursAgo = 1),
        createMockConfigFile(name = "config.json", sizeBytes = 2048),
        createMockPdfFile(name = "document.pdf", sizeMB = 2, hoursAgo = 2),
        createMockImageFile(name = "screenshot.png", sizeMB = 1, hoursAgo = 1),
        createMockTextFile(name = "readme.md", sizeKB = 8, hoursAgo = 3)
    )

    /**
     * Create mock empty state for SearcherWorkspacePage previews.
     */
    fun createMockEmptyState(workspaceId: Workspace.Id): SearcherWorkspaceViewModel.State =
        SearcherWorkspaceViewModel.State(
            id = workspaceId,
            searchTargets = listOf(
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0"))
            )
        )

    /**
     * Create mock state with search history for SearcherWorkspacePage previews.
     */
    fun createMockHistoryState(workspaceId: Workspace.Id): SearcherWorkspaceViewModel.State =
        SearcherWorkspaceViewModel.State(
            id = workspaceId,
            searchTargets = listOf(
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0"))
            ),
            searchHistory = createMockSearchHistory()
        )

    /**
     * Create mock state with search results for SearcherWorkspacePage previews.
     * Shows selection mode with 3 out of 5 items selected.
     */
    fun createMockResultsState(workspaceId: Workspace.Id): SearcherWorkspaceViewModel.State {
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

        return SearcherWorkspaceViewModel.State(
            id = workspaceId,
            filenameQuery = TextFieldValue("config"),
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
            selectionState = eu.darken.butler.searcher.ui.search.SearcherSelectionState(
                selectableResults = results,
                selectedResultIds = selectedResults.map { it.path.path }.toSet()
            ),
            availableActions = listOf(
                SearcherAction.Copy(selectedResults),
                SearcherAction.Cut(selectedResults),
                SearcherAction.Delete(selectedResults, trashEnabled = true),
                SearcherAction.Share(selectedResults),
                SearcherAction.SelectAll,
                SearcherAction.DeselectAll,
            ),
            trashEnabled = true,
        )
    }

    /**
     * Create mock searching state with multiple targets and varied progress for SearcherWorkspacePage previews.
     */
    fun createMockSearchingWithProgressState(workspaceId: Workspace.Id): SearcherWorkspaceViewModel.State =
        SearcherWorkspaceViewModel.State(
            id = workspaceId,
            filenameQuery = TextFieldValue("log"),
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
