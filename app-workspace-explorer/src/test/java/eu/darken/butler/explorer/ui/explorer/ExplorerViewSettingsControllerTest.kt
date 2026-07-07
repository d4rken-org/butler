package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.mockDataStoreValue
import java.io.File

class ExplorerViewSettingsControllerTest : BaseTest() {

    private val initialStyle = ExplorerViewStyle.default()
    private val initialSort = SortSettings()

    private val styleStore = mockDataStoreValue(initialStyle).apply {
        coEvery { update(any()) } returns DataStoreValue.Updated(initialStyle, initialStyle)
    }
    private val sortStore = mockDataStoreValue(initialSort).apply {
        coEvery { update(any()) } returns DataStoreValue.Updated(initialSort, initialSort)
    }

    private fun mockSettings(): ExplorerSettings = mockk<ExplorerSettings>().apply {
        every { defaultViewStyle } returns styleStore
        every { sortSettings } returns sortStore
    }

    private fun CoroutineScope.controller(
        settings: ExplorerSettings = mockSettings(),
    ) = ExplorerViewSettingsController(
        explorerSettings = settings,
        doLaunch = { block -> launch { block() } },
    )

    private fun fileItem(name: String): ExplorerItem.RegularFile {
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns LocalPath.build(File("/tmp/filter-test", name))
        }
        return ExplorerItem.RegularFile(lookup = lookup, mimeType = MimeInfo("text/plain"))
    }

    private fun directoryItem(name: String): ExplorerItem.RegularDirectory {
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns LocalPath.build(File("/tmp/filter-test", name))
        }
        return ExplorerItem.RegularDirectory(lookup = lookup)
    }

    private fun names(items: List<ExplorerItem>) = items.map { (it as ExplorerItem.Path).path.name }

    @Test
    fun `include pattern keeps only matching names`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("notes.txt"), fileItem("image.png"), fileItem("todo.txt"))

        // Simple (non-regex) mode is case-insensitive substring matching.
        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(includePattern = ".TXT"),
            useRegexPatterns = false,
        )

        names(result) shouldContainExactly listOf("notes.txt", "todo.txt")
    }

    @Test
    fun `exclude pattern wins over include pattern`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("notes.txt"), fileItem("secret.txt"))

        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(includePattern = ".txt", excludePattern = "secret"),
            useRegexPatterns = false,
        )

        names(result) shouldContainExactly listOf("notes.txt")
    }

    @Test
    fun `files-only filter drops directories`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("a.txt"), directoryItem("folder"))

        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(fileTypeFilter = FileTypeFilter.FILES_ONLY),
            useRegexPatterns = false,
        )

        names(result) shouldContainExactly listOf("a.txt")
    }

    @Test
    fun `folders-only filter drops files`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("a.txt"), directoryItem("folder"))

        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(fileTypeFilter = FileTypeFilter.FOLDERS_ONLY),
            useRegexPatterns = false,
        )

        names(result) shouldContainExactly listOf("folder")
    }

    @Test
    fun `regex patterns are honored when enabled`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("img_001.png"), fileItem("img_x.png"), fileItem("doc.txt"))

        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(includePattern = "img_\\d+\\.png"),
            useRegexPatterns = true,
        )

        names(result) shouldContainExactly listOf("img_001.png")
    }

    @Test
    fun `view style updates immediately and persists async`() = runTest {
        val controller = controller()
        val grid = ExplorerViewStyle.Grid()

        controller.updateViewStyle(grid)

        controller.viewStyle.value shouldBe grid
        runCurrent()
        coVerify { styleStore.update(any()) }
    }

    @Test
    fun `sort settings persist then publish`() = runTest {
        val controller = controller()
        val newSort = SortSettings().copy()

        controller.applySortSettings(newSort)

        controller.sortSettings.value shouldBe newSort
        coVerify { sortStore.update(any()) }
    }

    @Test
    fun `filter state applies and resets`() = runTest {
        val controller = controller()
        val filter = FilterState(includePattern = "*.md")

        controller.applyFilterState(filter)
        controller.filterState.value shouldBe filter

        controller.resetFilters()
        controller.filterState.value shouldBe FilterState()
    }
}
