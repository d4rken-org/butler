package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.ExplorerTabViewStore
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.sorting.rules.ExplorerTabSortStore
import eu.darken.butler.explorer.core.sorting.rules.FolderSortRulesRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.mockDataStoreValue
import java.io.File

class ProcessedListingTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private val viewPrefs = WorkspaceViewPrefs()

    private fun TestScope.controller() = ExplorerViewSettingsController(
        explorerSettings = mockk<ExplorerSettings>().apply {
            every { defaultViewStyle } returns mockDataStoreValue(ExplorerViewStyle.default())
            every { sortSettings } returns mockDataStoreValue(SortSettings())
        },
        folderSortRules = mockk<FolderSortRulesRepo>().apply {
            every { observeRulesFor(any()) } returns flowOf(emptyList())
        },
        tabSortStore = ExplorerTabSortStore(viewPrefs, json),
        tabViewStore = ExplorerTabViewStore(viewPrefs, json),
        json = json,
        workspaceId = Workspace.Id(),
        currentLocation = flowOf<ExplorerLocation?>(null),
        scope = backgroundScope,
        doLaunch = { block -> backgroundScope.launch { block() } },
    )

    private fun lookup(name: String, size: Long) = LocalPathLookup(
        lookedUp = LocalPath.build(File("/tmp/listing-test", name)),
        fileType = FileType.FILE,
        size = size,
        modifiedAt = null,
    )

    private fun file(name: String, size: Long = 1_024L) = ExplorerItem.RegularFile(
        lookup = lookup(name, size),
        mimeType = MimeInfo("text/plain"),
    )

    private fun directory(name: String) = ExplorerItem.RegularDirectory(
        lookup = lookup(name, 0L).copy(fileType = FileType.DIRECTORY),
    )

    private fun peek(name: String) = ExplorerItem.Peek(LocalPath.build(File("/tmp/listing-test", name)))

    /** What the ViewModel's pipeline does: filter with the tab's settings, then describe the result. */
    private fun ExplorerViewSettingsController.listingOf(
        locationId: String,
        rawItems: List<ExplorerItem>,
        filterState: FilterState = FilterState(),
        showHidden: Boolean = true,
    ) = processListing(
        locationId = locationId,
        rawItems = rawItems,
        items = applyFilters(rawItems, filterState, useRegexPatterns = false, showHidden = showHidden),
        filterState = filterState,
        useRegexPatterns = false,
        showHidden = showHidden,
    )

    @Test
    fun `the counts describe the listing as displayed`() = runTest {
        val controller = controller()

        val listing = controller.listingOf(
            locationId = "location://directory/tmp",
            rawItems = listOf(file("notes.txt", 100L), file(".env", 50L), directory("docs"), directory(".git")),
            showHidden = false,
        )

        listing.visibleFileCount shouldBe 1
        listing.visibleDirectoryCount shouldBe 1
        listing.visibleTotalSize shouldBe 100L
        listing.hiddenCount shouldBe 2
        listing.locationIsEmpty shouldBe false
    }

    /** Null rather than zero, the same way the loader reports a folder that totals nothing. */
    @Test
    fun `a listing without files has no total size`() = runTest {
        val controller = controller()

        controller.listingOf("location://directory/tmp", listOf(directory("docs"))).visibleTotalSize shouldBe null
    }

    @Test
    fun `nothing is hidden while the tab shows hidden entries`() = runTest {
        val controller = controller()

        val listing = controller.listingOf(
            locationId = "location://directory/tmp",
            rawItems = listOf(file(".env"), file("notes.txt")),
            showHidden = true,
        )

        listing.hiddenCount shouldBe 0
        listing.items.size shouldBe 2
    }

    @Test
    fun `an empty folder is not a filtered-empty one`() = runTest {
        val controller = controller()

        val listing = controller.listingOf("location://directory/tmp", emptyList())

        listing.locationIsEmpty shouldBe true
        listing.emptyRecovery shouldBe null
    }

    @Test
    fun `revealing recovers a listing only hiding emptied`() = runTest {
        val controller = controller()

        val listing = controller.listingOf(
            locationId = "location://directory/tmp",
            rawItems = listOf(file(".env"), directory(".git")),
            showHidden = false,
        )

        listing.emptyRecovery shouldBe EmptyRecovery.SHOW_HIDDEN
    }

    @Test
    fun `clearing the filters recovers a listing only they emptied`() = runTest {
        val controller = controller()

        val listing = controller.listingOf(
            locationId = "location://directory/tmp",
            rawItems = listOf(file("notes.txt")),
            filterState = FilterState(excludePattern = "notes"),
            showHidden = true,
        )

        listing.emptyRecovery shouldBe EmptyRecovery.RESET_FILTERS
    }

    @Test
    fun `either control recovers a listing hiding and filters emptied between them`() = runTest {
        val controller = controller()

        val listing = controller.listingOf(
            locationId = "location://directory/tmp",
            rawItems = listOf(file(".env"), file("notes.txt")),
            filterState = FilterState(excludePattern = "notes"),
            showHidden = false,
        )

        listing.emptyRecovery shouldBe EmptyRecovery.EITHER
    }

    /** Hiding and the filters both exclude everything, so neither control alone brings a row back. */
    @Test
    fun `only both together recover a listing each of them empties`() = runTest {
        val controller = controller()

        val listing = controller.listingOf(
            locationId = "location://directory/tmp",
            rawItems = listOf(file(".env"), file(".gitignore")),
            // The dot every hidden name starts with, so the pattern excludes exactly what hiding does
            filterState = FilterState(excludePattern = "."),
            showHidden = false,
        )

        listing.emptyRecovery shouldBe EmptyRecovery.SHOW_ALL
    }

    /** Peek entries pass every type filter, so a verdict taken now can be reversed by the classifier. */
    @Test
    fun `no recovery is offered while the listing still holds peek entries`() = runTest {
        val controller = controller()

        val listing = controller.listingOf(
            locationId = "location://directory/tmp",
            rawItems = listOf(peek(".env"), peek(".git")),
            showHidden = false,
        )

        listing.items.shouldBeEmpty()
        listing.emptyRecovery shouldBe null
    }

    /** Rows, counts and verdict come from one pass, so no emission can mix two folders. */
    @Test
    fun `navigating never pairs a folder's rows with another folder's numbers`() = runTest {
        val controller = controller()
        val folderA = listOf(file("a1.txt", 10L), file("a2.txt", 20L))
        val folderB = listOf(file(".b1"), file(".b2"))

        val listings = listOf(
            controller.listingOf("location://directory/a", folderA, showHidden = false),
            controller.listingOf("location://directory/b", folderB, showHidden = false),
        )

        listings.forEach { listing ->
            listing.visibleFileCount shouldBe listing.items.count { it is ExplorerItem.File }
            listing.visibleTotalSize shouldBe
                listing.items.filterIsInstance<ExplorerItem.File>().sumOf { it.lookup.size ?: 0L }.takeIf { it > 0 }
        }

        listings[0].locationId shouldBe "location://directory/a"
        listings[0].hiddenCount shouldBe 0
        listings[0].emptyRecovery shouldBe null
        listings[1].locationId shouldBe "location://directory/b"
        listings[1].items.shouldBeEmpty()
        listings[1].hiddenCount shouldBe 2
        listings[1].emptyRecovery shouldBe EmptyRecovery.SHOW_HIDDEN
    }

    @Test
    fun `revealing from an empty filtered listing drops the verdict with the same emission`() = runTest {
        val controller = controller()
        val rawItems = listOf(file(".env", 30L), directory(".git"))

        val hiding = controller.listingOf("location://directory/tmp", rawItems, showHidden = false)
        val showing = controller.listingOf("location://directory/tmp", rawItems, showHidden = true)

        hiding.items.shouldBeEmpty()
        hiding.hiddenCount shouldBe 2
        hiding.emptyRecovery shouldBe EmptyRecovery.SHOW_HIDDEN

        showing.items shouldContainExactly rawItems
        showing.visibleFileCount shouldBe 1
        showing.visibleDirectoryCount shouldBe 1
        showing.visibleTotalSize shouldBe 30L
        showing.hiddenCount shouldBe 0
        showing.emptyRecovery shouldBe null
    }
}
