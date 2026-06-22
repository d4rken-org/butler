package eu.darken.butler.explorer.core.engine

import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DirectoryInfoCountsTest : BaseTest() {

    private val emptyInfo = ExplorerLocation.Directory.Info(fileCount = 0, directoryCount = 0)

    @Test
    fun `withCountsFrom - recomputes counts after items are added`() {
        val items = listOf(
            MockDataProvider.createMockDirectory("folderA"),
            MockDataProvider.createMockRegularFile("file1.txt"),
            MockDataProvider.createMockRegularFile("file2.txt"),
        )

        val updated = emptyInfo.withCountsFrom(items)

        updated.directoryCount shouldBe 1
        updated.fileCount shouldBe 2
    }

    @Test
    fun `withCountsFrom - empty items yields zero counts`() {
        val updated = ExplorerLocation.Directory.Info(fileCount = 3, directoryCount = 2)
            .withCountsFrom(emptyList())

        updated.directoryCount shouldBe 0
        updated.fileCount shouldBe 0
    }

    @Test
    fun `withCountsFrom - peek items are ignored`() {
        val items = listOf(
            MockDataProvider.createMockRegularFile("file1.txt"),
            MockDataProvider.createMockPeek("loading.txt"),
        )

        val updated = emptyInfo.withCountsFrom(items)

        updated.fileCount shouldBe 1
        updated.directoryCount shouldBe 0
    }

    @Test
    fun `withCountsFrom - preserves unrelated info fields`() {
        val info = ExplorerLocation.Directory.Info(
            fileCount = 0,
            directoryCount = 0,
            totalSize = 9000L,
            isWritable = true,
        )

        val updated = info.withCountsFrom(listOf(MockDataProvider.createMockRegularFile()))

        updated.totalSize shouldBe 9000L
        updated.isWritable shouldBe true
        updated.fileCount shouldBe 1
    }
}
