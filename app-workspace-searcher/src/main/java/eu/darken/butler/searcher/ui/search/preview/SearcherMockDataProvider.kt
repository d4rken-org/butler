package eu.darken.butler.searcher.ui.search.preview

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
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
}
