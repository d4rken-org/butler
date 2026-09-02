package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.toFileListing
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a viewer opened from this tab may step through: the listing the Explorer shows, files only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerFileListingTest {

    private val directory = LocalPath.build("/storage/emulated/0/DCIM")

    private val directoryLocation = ExplorerLocation.Directory(
        path = directory,
        items = emptyList(),
        progress = null,
    )

    private fun lookup(name: String, type: FileType) = LocalPathLookup(
        lookedUp = directory.child(name),
        fileType = type,
        size = 1L,
        modifiedAt = null,
    )

    private fun file(name: String) = ExplorerItem.RegularFile(
        lookup = lookup(name, FileType.FILE),
        mimeType = MimeInfo("image/jpeg"),
    )

    private fun symlink(name: String) = ExplorerItem.SymbolicLink(
        lookup = lookup(name, FileType.SYMBOLIC_LINK),
        mimeType = MimeInfo("image/jpeg"),
    )

    private fun folder(name: String) = ExplorerItem.RegularDirectory(
        lookup = lookup(name, FileType.DIRECTORY),
    )

    private fun ready(location: ExplorerLocation?) = ExplorerWorkspace.State.Ready(currentLocation = location)

    private val files = listOf(directory.child("a.jpg"), directory.child("b.jpg"))

    @Test
    fun `only files make the listing, in display order`() {
        val items = listOf(
            folder("albums"),
            file("b.jpg"),
            symlink("link.jpg"),
            ExplorerItem.Peek(directory.child("peek.jpg")),
            file("a.jpg"),
            ExplorerItem.Trash.Root(
                itemId = Uuid.random(),
                deletedAt = Instant.fromEpochMilliseconds(0),
                originalLookup = mockk { every { lookedUp } returns directory.child("deleted.jpg") },
                trashLookup = null,
            ),
        )

        items.toFileListing() shouldBe listOf(
            directory.child("b.jpg"),
            directory.child("link.jpg"),
            directory.child("a.jpg"),
        )
    }

    @Test
    fun `an empty listing has nothing to step through`() {
        emptyList<ExplorerItem>().toFileListing() shouldBe emptyList()
    }

    @Test
    fun `a publication is read while the tab is on the location it was computed for`() {
        val publication = FileListingPublication(directoryLocation.locationId, files)

        resolveFileListing(ready(directoryLocation), publication) shouldBe files
    }

    @Test
    fun `a publication for another location is not read`() {
        val publication = FileListingPublication(ExplorerLocation.Home().locationId, files)

        resolveFileListing(ready(directoryLocation), publication) shouldBe emptyList()
    }

    @Test
    fun `a tab that shows no location has nothing to step through`() {
        val publication = FileListingPublication(directoryLocation.locationId, files)

        resolveFileListing(ready(null), publication) shouldBe emptyList()
        resolveFileListing(ExplorerWorkspace.State.Initializing, publication) shouldBe emptyList()
        resolveFileListing(ExplorerWorkspace.State.Error(RuntimeException("Nope")), publication) shouldBe emptyList()
    }

    @Test
    fun `a tab that never settled on a location publishes nothing`() {
        val workspace = testExplorerWorkspace(ExplorerArguments.Default(startPath = directory))

        workspace.fileListing.value shouldBe emptyList()

        workspace.publishFileListing(directoryLocation.locationId, files)

        // Read through the interface: that is the only thing the viewer knows about this tab.
        (workspace as Workspace.FileListingSource).fileListing.value shouldBe emptyList()
    }

    @Test
    fun `the published listing is what a stepping viewer reads`() = runTest {
        val engineLocation = MutableStateFlow(BrowsingEngine.State())
        val engine = mockk<BrowsingEngine>(relaxed = true).apply {
            every { location } returns engineLocation
        }
        val workspace = testExplorerWorkspace(
            ExplorerArguments.Default(startPath = directory),
            UnconfinedTestDispatcher(testScheduler),
            browsingEngine = engine,
        )

        try {
            advanceUntilIdle()
            engineLocation.value = BrowsingEngine.State(
                location = directoryLocation,
                breadcrumbs = emptyList(),
                target = ExplorerNavigation.Target.Directory(directory),
            )
            advanceUntilIdle()

            workspace.publishFileListing(directoryLocation.locationId, files)
            advanceUntilIdle()

            // Read through the interface: that is the only thing the viewer knows about this tab.
            (workspace as Workspace.FileListingSource).fileListing.value shouldBe files

            workspace.publishFileListing(ExplorerLocation.Home().locationId, files)
            advanceUntilIdle()
            (workspace as Workspace.FileListingSource).fileListing.value shouldBe emptyList()
        } finally {
            workspace.release()
        }
    }
}
