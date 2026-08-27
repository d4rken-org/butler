package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.archive.ArchivePathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.smb.SmbPathLookup
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class DirectoryActionProviderTest : BaseTest() {

    private val favoritesRepo = mockk<ExplorerFavoritesRepo>().apply {
        every { isFavorite(any()) } returns false
    }

    private fun provider() = DirectoryActionProvider(favoritesRepo)

    private fun directory(items: List<ExplorerItem.Path>?) = ExplorerLocation.Directory(
        items = items,
        info = ExplorerLocation.Directory.Info(isWritable = true),
        path = LocalPath.build("/home/user/dir"),
    )

    private fun actionsFor(items: List<ExplorerItem.Path>?) = provider().getActions(
        location = directory(items),
        selectionState = ExplorerSelectionState(),
        viewStyle = ExplorerViewStyle.List(),
        trashEnabled = false,
    )

    private fun List<ExplorerActionBarItem>.hasSecondaryBrowsingActions(): Boolean =
        any { it is ExplorerActionBarItem.Common.Sort } &&
            any { it is ExplorerActionBarItem.Common.Filter } &&
            any { it is ExplorerActionBarItem.Common.UpdateViewStyle }

    @Test
    fun `empty folder hides sort, filter and view-style actions`() {
        val actions = actionsFor(emptyList())

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.UpdateViewStyle } shouldBe false
        // The always-relevant browsing actions stay.
        actions.any { it is ExplorerActionBarItem.Directory.Create } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
    }

    @Test
    fun `non-empty folder shows sort, filter and view-style actions`() {
        val actions = actionsFor(listOf(MockDataProvider.createMockRegularFile()))

        actions.hasSecondaryBrowsingActions() shouldBe true
    }

    @Test
    fun `loading folder with null items shows the actions to avoid flicker`() {
        val actions = actionsFor(null)

        actions.hasSecondaryBrowsingActions() shouldBe true
    }

    private val container = LocalPath.build("/home/user/a.zip")

    private fun archiveEntry(vararg segments: String) = ExplorerItem.RegularFile(
        lookup = ArchivePathLookup(
            lookedUp = ArchivePath(container, segments.toList()),
            fileType = FileType.FILE,
            size = 10L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("text/plain"),
    )

    private fun selectionActions(
        location: ExplorerLocation,
        selected: Set<ExplorerItem>,
    ) = provider().getActions(
        location = location,
        selectionState = ExplorerSelectionState(selectedItems = selected),
        viewStyle = ExplorerViewStyle.List(),
        trashEnabled = false,
    )

    @Test
    fun `archive location hides Rename, Cut and Delete but keeps Copy`() {
        val archiveLocation = ExplorerLocation.Directory(
            items = null,
            // Even with a stale/true isWritable, the archive path type forces read-only.
            info = ExplorerLocation.Directory.Info(isWritable = true),
            path = ArchivePath(container, listOf("sub")),
        )
        val actions = selectionActions(archiveLocation, setOf(archiveEntry("sub", "file.txt")))

        actions.any { it is ExplorerActionBarItem.Directory.Rename } shouldBe false
        actions.any { it is ExplorerActionBarItem.Directory.Cut } shouldBe false
        actions.any { it is ExplorerActionBarItem.Directory.Delete } shouldBe false
        // Copy-out of archive entries must stay available.
        actions.any { it is ExplorerActionBarItem.Directory.Copy } shouldBe true
    }

    @Test
    fun `writable local location shows Rename, Cut and Delete for a selection`() {
        val actions = selectionActions(directory(null), setOf(MockDataProvider.createMockRegularFile()))

        actions.any { it is ExplorerActionBarItem.Directory.Rename } shouldBe true
        actions.any { it is ExplorerActionBarItem.Directory.Cut } shouldBe true
        actions.any { it is ExplorerActionBarItem.Directory.Delete } shouldBe true
    }

    @Test
    fun `an archive entry in a selection over a real directory still hides Rename, Cut and Delete`() {
        // A stale/transitioning selection can carry an archive entry into a real, writable directory,
        // so the gating keys off the selected paths, not just the location.
        val actions = selectionActions(directory(null), setOf(archiveEntry("sub", "file.txt")))

        actions.any { it is ExplorerActionBarItem.Directory.Rename } shouldBe false
        actions.any { it is ExplorerActionBarItem.Directory.Cut } shouldBe false
        actions.any { it is ExplorerActionBarItem.Directory.Delete } shouldBe false
    }

    @Test
    fun `a nested archive entry does not offer Extract`() {
        // "inner.zip" is itself inside an archive; the archive service can't open an ArchivePath as a
        // container, so Extract must not appear even though the name looks like an archive.
        val archiveLocation = ExplorerLocation.Directory(
            items = null,
            info = ExplorerLocation.Directory.Info(isWritable = false),
            path = ArchivePath(container, listOf("sub")),
        )
        val actions = selectionActions(archiveLocation, setOf(archiveEntry("sub", "inner.zip")))

        actions.any { it is ExplorerActionBarItem.Directory.Extract } shouldBe false
    }

    private fun networkFile(name: String) = ExplorerItem.RegularFile(
        lookup = SmbPathLookup(
            lookedUp = SmbPath(LOCATION_ID, listOf(name)),
            fileType = FileType.FILE,
            size = 10L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("text/plain"),
    )

    @Test
    fun `a selection holding a network file does not offer Share`() {
        val actions = selectionActions(
            directory(null),
            setOf(MockDataProvider.createMockRegularFile(), networkFile("remote.txt")),
        )

        actions.any { it is ExplorerActionBarItem.Directory.Share } shouldBe false
    }

    @Test
    fun `a purely local selection offers Share`() {
        val actions = selectionActions(directory(null), setOf(MockDataProvider.createMockRegularFile()))

        actions.any { it is ExplorerActionBarItem.Directory.Share } shouldBe true
    }

    @Test
    fun `a writable network location enables Cut and Delete`() {
        val networkLocation = ExplorerLocation.Directory(
            items = null,
            info = ExplorerLocation.Directory.Info(isWritable = true),
            path = SmbPath(LOCATION_ID, listOf("media")),
        )
        val actions = selectionActions(networkLocation, setOf(networkFile("remote.txt")))

        actions.filterIsInstance<ExplorerActionBarItem.Directory.Cut>().single().isEnabled shouldBe true
        actions.filterIsInstance<ExplorerActionBarItem.Directory.Delete>().single().isEnabled shouldBe true
    }

    @Test
    fun `a real archive file offers Extract`() {
        val actions = selectionActions(directory(null), setOf(MockDataProvider.createMockRegularFile("real.zip")))

        actions.any { it is ExplorerActionBarItem.Directory.Extract } shouldBe true
    }

    companion object {
        private val LOCATION_ID = Uuid.parse("11111111-2222-3333-4444-555555555555")
    }
}
