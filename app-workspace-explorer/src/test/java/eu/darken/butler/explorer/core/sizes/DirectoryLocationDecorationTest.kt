package eu.darken.butler.explorer.core.sizes

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class DirectoryLocationDecorationTest : BaseTest() {

    private val scannedAt = Instant.parse("2026-09-07T14:32:00Z")

    private fun directoryItem(path: String) = ExplorerItem.RegularDirectory(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = FileType.DIRECTORY,
            size = null,
            modifiedAt = null,
        ),
    )

    private fun fileItem(path: String) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = FileType.FILE,
            size = 12L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("text/plain"),
    )

    private fun snapshotWith(scan: DirectoryScan) = DirectorySizeStore.Snapshot(
        scans = mapOf(scan.root.path to scan),
    )

    private val scan = DirectoryScan(
        root = LocalPath.build("/a"),
        scannedAt = scannedAt,
        sizes = mapOf(
            "/a" to DirectorySize(100L, true),
            "/a/known" to DirectorySize(40L, false),
        ),
        itemCount = 2,
        errorCount = 0,
    )

    @Test
    fun `folders the scan knows get their size, the others stay bare`() {
        val location = ExplorerLocation.Directory(
            path = LocalPath.build("/a"),
            items = listOf(directoryItem("/a/known"), directoryItem("/a/unknown"), fileItem("/a/file")),
        )

        val decorated = location.withDirectorySizes(snapshotWith(scan)) as ExplorerLocation.Directory
        val items = decorated.items!!

        (items[0] as ExplorerItem.RegularDirectory).computedSize shouldBe DirectorySize(40L, false)
        (items[1] as ExplorerItem.RegularDirectory).computedSize shouldBe null
        items[2] shouldBe fileItem("/a/file")
    }

    @Test
    fun `a location no scan covers is handed back untouched`() {
        val location = ExplorerLocation.Directory(
            path = LocalPath.build("/elsewhere"),
            items = listOf(directoryItem("/elsewhere/dir")),
        )

        location.withDirectorySizes(snapshotWith(scan)) shouldBeSameInstanceAs location
    }

    @Test
    fun `a location that is not a directory is handed back untouched`() {
        val location = ExplorerLocation.Home(items = emptyList())

        location.withDirectorySizes(snapshotWith(scan)) shouldBeSameInstanceAs location
    }
}
