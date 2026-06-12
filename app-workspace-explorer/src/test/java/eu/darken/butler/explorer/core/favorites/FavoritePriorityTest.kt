package eu.darken.butler.explorer.core.favorites

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class FavoritePriorityTest : BaseTest() {

    private fun mockDirectory(path: APath<*>, name: String = path.path): ExplorerItem.Directory =
        mockk<ExplorerItem.RegularDirectory>().also {
            every { it.path } returns path
            every { it.id } returns "dir-${name.hashCode()}"
        }

    private fun mockFile(path: APath<*>, name: String = path.path): ExplorerItem.File =
        mockk<ExplorerItem.RegularFile>().also {
            every { it.path } returns path
            every { it.id } returns "file-${name.hashCode()}"
        }

    private fun directoryLocation(path: APath<*> = LocalPath.build("/parent")): ExplorerLocation.Directory =
        ExplorerLocation.Directory(path = path)

    @Test
    fun `directory location with no favorites returns input unchanged`() {
        val a = mockDirectory(LocalPath.build("/p/a"), "a")
        val b = mockDirectory(LocalPath.build("/p/b"), "b")
        val items = listOf(a, b)

        val result = applyFavoritePriority(
            items = items,
            location = directoryLocation(),
            pickerConfig = null,
            favoritePaths = emptyList(),
        )
        result shouldBe items
    }

    @Test
    fun `directory location promotes favorited dir to the top, preserves rest order`() {
        val a = mockDirectory(LocalPath.build("/p/a"), "a")
        val b = mockDirectory(LocalPath.build("/p/b"), "b")
        val c = mockDirectory(LocalPath.build("/p/c"), "c")

        val result = applyFavoritePriority(
            items = listOf(a, b, c),
            location = directoryLocation(),
            pickerConfig = null,
            favoritePaths = listOf(LocalPath.build("/p/b")),
        )
        result shouldContainExactly listOf(b, a, c)
    }

    @Test
    fun `multiple favorites preserve their relative input order within the pinned group`() {
        val a = mockDirectory(LocalPath.build("/p/a"), "a")
        val b = mockDirectory(LocalPath.build("/p/b"), "b")
        val c = mockDirectory(LocalPath.build("/p/c"), "c")
        val d = mockDirectory(LocalPath.build("/p/d"), "d")

        // Sort produced [d, c, b, a] (e.g. reverse alphabetical). Both c and b are favorites.
        val result = applyFavoritePriority(
            items = listOf(d, c, b, a),
            location = directoryLocation(),
            pickerConfig = null,
            favoritePaths = listOf(LocalPath.build("/p/b"), LocalPath.build("/p/c")),
        )
        // c, b come from sort order — pinned group reflects the same reverse order. Then d, a.
        result shouldContainExactly listOf(c, b, d, a)
    }

    @Test
    fun `Home location is not reordered`() {
        val a = mockDirectory(LocalPath.build("/p/a"), "a")
        val b = mockDirectory(LocalPath.build("/p/b"), "b")
        val items = listOf(a, b)

        val result = applyFavoritePriority(
            items = items,
            location = ExplorerLocation.Home(),
            pickerConfig = null,
            favoritePaths = listOf(LocalPath.build("/p/b")),
        )
        result shouldBe items
    }

    @Test
    fun `Device location is not reordered even when storage roots are favorited`() {
        val a = mockDirectory(LocalPath.build("/p/a"), "a")
        val b = mockDirectory(LocalPath.build("/p/b"), "b")
        val items = listOf(a, b)

        val result = applyFavoritePriority(
            items = items,
            location = ExplorerLocation.Device(),
            pickerConfig = null,
            favoritePaths = listOf(LocalPath.build("/p/a"), LocalPath.build("/p/b")),
        )
        result shouldBe items
    }

    @Test
    fun `picker mode is never reordered`() {
        val a = mockDirectory(LocalPath.build("/p/a"), "a")
        val b = mockDirectory(LocalPath.build("/p/b"), "b")
        val items = listOf(a, b)

        val pickerConfig = mockk<PickerConfig>()
        val result = applyFavoritePriority(
            items = items,
            location = directoryLocation(),
            pickerConfig = pickerConfig,
            favoritePaths = listOf(LocalPath.build("/p/b")),
        )
        result shouldBe items
    }

    @Test
    fun `favorited file is NOT promoted - only directories get the pin`() {
        val dir = mockDirectory(LocalPath.build("/p/dir"), "dir")
        val file = mockFile(LocalPath.build("/p/file.txt"), "file.txt")

        val result = applyFavoritePriority(
            items = listOf(dir, file),
            location = directoryLocation(),
            pickerConfig = null,
            favoritePaths = listOf(LocalPath.build("/p/file.txt")), // file is favorited
        )
        // No reorder — pinning a file would break "dirs before files" sort contract
        result shouldContainExactly listOf(dir, file)
    }

    @Test
    fun `LocalPath and SAFPath with identical string remain distinct`() {
        // Same rendered string but different APath types — should NOT be considered equal
        val localFavoritePath = LocalPath.build("/Documents")
        val safPath = SAFPath(
            treeRoot = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            segments = listOf("Documents"),
        )
        val safDir = mockDirectory(safPath, "saf-Documents")

        val result = applyFavoritePriority(
            items = listOf(safDir),
            location = directoryLocation(),
            pickerConfig = null,
            favoritePaths = listOf(localFavoritePath),
        )
        // SAF dir should NOT be pinned because the favorite is a LocalPath
        result shouldContainExactly listOf(safDir)
    }

    @Test
    fun `nothing to pin returns input unchanged identity-wise`() {
        val a = mockDirectory(LocalPath.build("/p/a"), "a")
        val items = listOf(a)

        val result = applyFavoritePriority(
            items = items,
            location = directoryLocation(),
            pickerConfig = null,
            favoritePaths = listOf(LocalPath.build("/some/other/path")),
        )
        result shouldBe items
    }
}
