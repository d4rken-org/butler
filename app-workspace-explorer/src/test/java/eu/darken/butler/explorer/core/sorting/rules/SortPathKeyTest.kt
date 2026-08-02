package eu.darken.butler.explorer.core.sorting.rules

import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SortPathKeyTest : BaseTest() {

    private fun saf(treeRoot: String, vararg segments: String) = SAFPath(treeRoot, segments.toList())

    @Test
    fun `local keys are the normalized path components`() {
        LocalPath.build("/storage/emulated/0/Download").sortPathKey() shouldBe
            "local/storage/emulated/0/Download"
        LocalPath.build("/").sortPathKey() shouldBe "local"
    }

    @Test
    fun `local ancestors stop at the root`() {
        LocalPath.build("/storage/emulated/0").sortAncestorKeys() shouldBe listOf(
            "local/storage/emulated/0",
            "local/storage/emulated",
            "local/storage",
            "local",
        )
        LocalPath.build("/").sortAncestorKeys() shouldBe listOf("local")
    }

    @Test
    fun `dot and dot-dot segments are normalized away`() {
        LocalPath.build("/storage/./emulated/0/../0/Download").sortPathKey() shouldBe
            LocalPath.build("/storage/emulated/0/Download").sortPathKey()
    }

    /**
     * The whole point of comparing components instead of string prefixes: a sibling whose name
     * merely starts with the folder's name must never inherit its subtree rule.
     */
    @Test
    fun `a sibling with a longer name is not a descendant`() {
        val photos = LocalPath.build("/sdcard/Photos")
        val backup = LocalPath.build("/sdcard/PhotosBackup")

        backup.sortAncestorKeys() shouldNotBe photos.sortPathKey()
        backup.sortAncestorKeys().contains(photos.sortPathKey()) shouldBe false
    }

    @Test
    fun `a colon in a folder name cannot collide with a nested path`() {
        LocalPath.build("/sdcard/Budget:2026").sortPathKey() shouldNotBe
            LocalPath.build("/sdcard/Budget/2026").sortPathKey()
    }

    @Test
    fun `a slash-bearing component is escaped rather than splitting the key`() {
        saf("content://auth/tree/primary%3AHoliday", "a/b").sortPathKey() shouldNotBe
            saf("content://auth/tree/primary%3AHoliday", "a", "b").sortPathKey()
    }

    @Test
    fun `a local file named with an exclamation mark cannot collide with an archive key`() {
        val bang = LocalPath.build("/sdcard/!archive")
        val archive = ArchivePath(container = LocalPath.build("/sdcard/foo.zip"), segments = emptyList())

        bang.sortPathKey() shouldNotBe archive.sortPathKey()
        bang.sortPathKey() shouldBe "local/sdcard/!archive"
        archive.sortPathKey() shouldBe "local/sdcard/foo.zip/!archive"
    }

    @Test
    fun `archive ancestors stop at the archive root`() {
        val entry = ArchivePath(
            container = LocalPath.build("/sdcard/Download/foo.zip"),
            segments = listOf("docs", "notes"),
        )

        entry.sortAncestorKeys() shouldBe listOf(
            "local/sdcard/Download/foo.zip/!archive/docs/notes",
            "local/sdcard/Download/foo.zip/!archive/docs",
            "local/sdcard/Download/foo.zip/!archive",
        )
    }

    /**
     * A rule saved on the containing folder must not reach inside the archives that live in it.
     */
    @Test
    fun `archive ancestors never walk out into the container`() {
        val entry = ArchivePath(
            container = LocalPath.build("/sdcard/Download/foo.zip"),
            segments = listOf("docs"),
        )

        entry.sortAncestorKeys().contains(LocalPath.build("/sdcard/Download").sortPathKey()) shouldBe false
    }

    /**
     * Grant-independence: whether the user granted the whole volume or exactly this folder, the same
     * folder has to produce the same key AND the same ancestor list, or a rule would apply under one
     * grant and vanish under the other.
     */
    @Test
    fun `broad and narrow SAF grants of the same folder agree`() {
        val broad = saf("content://com.android.externalstorage.documents/tree/primary%3A", "Pictures", "Trips")
        val narrow = saf("content://com.android.externalstorage.documents/tree/primary%3APictures%2FTrips")

        broad.sortPathKey() shouldBe narrow.sortPathKey()
        broad.sortAncestorKeys() shouldBe narrow.sortAncestorKeys()
    }

    @Test
    fun `SAF ancestors stop at the volume root`() {
        val path = saf("content://com.android.externalstorage.documents/tree/primary%3A", "Pictures", "Trips")

        path.sortAncestorKeys() shouldBe listOf(
            "saf/com.android.externalstorage.documents/primary/Pictures/Trips",
            "saf/com.android.externalstorage.documents/primary/Pictures",
            "saf/com.android.externalstorage.documents/primary",
        )
    }

    @Test
    fun `different SAF authorities do not share keys`() {
        saf("content://com.android.externalstorage.documents/tree/primary%3ADocs").sortPathKey() shouldNotBe
            saf("content://com.other.provider/tree/primary%3ADocs").sortPathKey()
    }
}
