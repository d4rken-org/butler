package eu.darken.butler.explorer.ui.explorer

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File

class ExplorerSelectionControllerTest : BaseTest() {

    private fun fileItem(name: String): ExplorerItem.RegularFile {
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns LocalPath.build(File("/tmp/selection-test", name))
            every { this@apply.name } returns name
        }
        return ExplorerItem.RegularFile(lookup = lookup, mimeType = MimeInfo("text/plain"))
    }

    private fun storageItem(id: String): ExplorerItem.Storage.Local = ExplorerItem.Storage.Local(
        localId = id,
        displayName = mockk(),
        displayIcon = mockk<ImageVector>(),
        target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/$id")),
    )

    private fun pickerConfig(selection: PickerConfig.Selection): PickerConfig = mockk<PickerConfig>().apply {
        every { this@apply.selection } returns selection
    }

    private fun mockWorkspace(config: PickerConfig? = null): ExplorerWorkspace = mockk<ExplorerWorkspace>().apply {
        every { pickerConfig } returns config
        coEvery { updateSaveAsFilename(any()) } just Runs
    }

    private fun CoroutineScope.controller(
        config: PickerConfig? = null,
        workspace: ExplorerWorkspace = mockWorkspace(config),
        selectableItems: Set<ExplorerItem> = emptySet(),
        currentLocationId: () -> String? = { null },
        navigate: (ExplorerItem) -> Unit = {},
    ) = ExplorerSelectionController(
        pickerConfig = { config },
        workspace = { workspace },
        selectableItems = { selectableItems },
        currentLocationId = currentLocationId,
        navigate = navigate,
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `toggle adds and removes items`() = runTest {
        val controller = controller()
        val item = fileItem("a.txt")

        controller.toggle(item)
        controller.selectedItems.value shouldBe setOf(item)

        controller.toggle(item)
        controller.selectedItems.value shouldBe emptySet()
    }

    @Test
    fun `unselectable items are ignored`() = runTest {
        val controller = controller()
        // A trash item without a trash lookup is unavailable and therefore not selectable.
        val unavailable = ExplorerItem.Trash.Root(
            itemId = kotlin.uuid.Uuid.random(),
            deletedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
            originalLookup = mockk(),
            trashLookup = null,
        )

        controller.toggle(unavailable)

        controller.selectedItems.value shouldBe emptySet()
    }

    @Test
    fun `directory-single picker treats storage selection as radio buttons`() = runTest {
        val controller = controller(config = pickerConfig(PickerConfig.Selection.DirectorySingle))
        val first = storageItem("first")
        val second = storageItem("second")

        controller.toggle(first)
        controller.selectedItems.value shouldBe setOf(first)

        // Selecting another storage REPLACES instead of adding.
        controller.toggle(second)
        controller.selectedItems.value shouldBe setOf(second)

        // Selecting the same one again deselects.
        controller.toggle(second)
        controller.selectedItems.value shouldBe emptySet()
    }

    @Test
    fun `select-all variants use the selectable set`() = runTest {
        val file = fileItem("a.txt")
        val controller = controller(selectableItems = setOf(file))

        controller.selectAll()
        runCurrent()
        controller.selectedItems.value shouldBe setOf(file)

        controller.clear()
        controller.selectAllFiles()
        runCurrent()
        controller.selectedItems.value shouldBe setOf(file)

        controller.clear()
        controller.selectAllFolders()
        runCurrent()
        controller.selectedItems.value shouldBe emptySet()
    }

    @Test
    fun `single-select pickers reject bulk selection`() = runTest {
        val first = fileItem("a.txt")
        val second = fileItem("b.txt")
        val items = setOf(first, second)

        listOf(
            PickerConfig.Selection.FileSingle,
            PickerConfig.Selection.DirectorySingle,
            PickerConfig.Selection.SaveAs(suggestedFilename = ""),
        ).forEach { selection ->
            val controller = controller(config = pickerConfig(selection), selectableItems = items)

            controller.selectAll()
            runCurrent()
            controller.selectedItems.value shouldBe emptySet()

            controller.selectAllFiles()
            runCurrent()
            controller.selectedItems.value shouldBe emptySet()

            controller.selectAllFolders()
            runCurrent()
            controller.selectedItems.value shouldBe emptySet()

            controller.set(items)
            controller.selectedItems.value shouldBe emptySet()

            // A single item is still a valid selection
            controller.set(setOf(first))
            controller.selectedItems.value shouldBe setOf(first)
        }
    }

    @Test
    fun `single-select pickers replace instead of accumulate on toggle`() = runTest {
        val controller = controller(config = pickerConfig(PickerConfig.Selection.FileSingle))
        val first = fileItem("a.txt")
        val second = fileItem("b.txt")

        controller.toggle(first)
        controller.selectedItems.value shouldBe setOf(first)

        controller.toggle(second)
        controller.selectedItems.value shouldBe setOf(second)

        controller.toggle(second)
        controller.selectedItems.value shouldBe emptySet()
    }

    @Test
    fun `multi-select pickers still allow bulk selection`() = runTest {
        val first = fileItem("a.txt")
        val second = fileItem("b.txt")
        val controller = controller(
            config = pickerConfig(PickerConfig.Selection.FileMulti),
            selectableItems = setOf(first, second),
        )

        controller.selectAll()
        runCurrent()

        controller.selectedItems.value shouldBe setOf(first, second)
    }

    @Test
    fun `file tap toggles selection in file-multi picker mode`() = runTest {
        val config = pickerConfig(PickerConfig.Selection.FileMulti)
        val controller = controller(config = config)
        val item = fileItem("a.txt")

        controller.onItemClick(item)
        runCurrent()

        controller.selectedItems.value shouldBe setOf(item)
    }

    @Test
    fun `file tap prefills the filename in save-as mode`() = runTest {
        val config = pickerConfig(PickerConfig.Selection.SaveAs(suggestedFilename = ""))
        val workspace = mockWorkspace(config)
        val controller = controller(config = config, workspace = workspace)

        controller.onItemClick(fileItem("report.pdf"))
        runCurrent()

        coVerify { workspace.updateSaveAsFilename("report.pdf") }
        controller.selectedItems.value shouldBe emptySet()
    }

    @Test
    fun `tap navigates when nothing is selected and toggles when selection is active`() = runTest {
        var navigated: ExplorerItem? = null
        val controller = controller(navigate = { navigated = it })
        val first = fileItem("a.txt")
        val second = fileItem("b.txt")

        controller.onItemClick(first)
        runCurrent()
        navigated shouldBe first

        controller.toggle(first)
        controller.onItemClick(second)
        runCurrent()
        controller.selectedItems.value shouldBe setOf(first, second)
    }

    @Test
    fun `long press only selects where allowed`() = runTest {
        val file = fileItem("a.txt")

        // Normal mode: allowed.
        val normal = controller()
        normal.onItemLongClick(file)
        normal.selectedItems.value shouldBe setOf(file)

        // Single-select file picker: not allowed for files.
        val single = controller(config = pickerConfig(PickerConfig.Selection.FileSingle))
        single.onItemLongClick(file)
        single.selectedItems.value shouldBe emptySet()

        // DirectorySingle: allowed for storage items.
        val dirSingle = controller(config = pickerConfig(PickerConfig.Selection.DirectorySingle))
        val storage = storageItem("vol")
        dirSingle.onItemLongClick(storage)
        dirSingle.selectedItems.value shouldBe setOf(storage)
    }

    @Test
    fun `a picker routes a location that needs a sign-in to the form instead of selecting it`() = runTest {
        val signInRequired = MockDataProvider.createMockStorageNetwork(
            name = "Work NAS",
            status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
        )
        var navigated: ExplorerItem? = null
        val controller = controller(
            config = pickerConfig(PickerConfig.Selection.DirectorySingle),
            selectableItems = setOf(signInRequired),
            navigate = { navigated = it },
        )

        controller.onItemClick(signInRequired)
        runCurrent()
        navigated shouldBe signInRequired
        controller.selectedItems.value shouldBe emptySet()

        navigated = null
        controller.onItemLongClick(signInRequired)
        runCurrent()
        navigated shouldBe signInRequired
        controller.selectedItems.value shouldBe emptySet()
    }

    @Test
    fun `long press stops changing the selection once one exists`() = runTest {
        val first = fileItem("a.txt")
        val second = fileItem("b.txt")
        val controller = controller()

        controller.onItemLongClick(first)
        controller.selectedItems.value shouldBe setOf(first)

        controller.onItemLongClick(second)
        controller.selectedItems.value shouldBe setOf(first)

        controller.onItemLongClick(first)
        controller.selectedItems.value shouldBe setOf(first)
    }

    private fun lookupItem(name: String) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(LISTING_PATH, name),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("text/plain"),
    )

    private fun listing(
        vararg items: ExplorerItem.Path,
        path: String = LISTING_PATH,
        loading: Boolean = false,
    ) = ExplorerLocation.Directory(
        items = items.toList(),
        path = LocalPath.build(path),
        progress = if (loading) Progress.Data() else null,
    )

    private fun CoroutineScope.pruningController() = controller(
        currentLocationId = { listing().locationId },
    )

    @Test
    fun `pruning drops items that vanished from the listing`() = runTest {
        val kept = lookupItem("a.txt")
        val gone = lookupItem("b.txt")
        val controller = pruningController()
        controller.set(setOf(kept, gone))

        controller.pruneAgainst(listing(kept))

        controller.selectedItems.value shouldBe setOf(kept)
    }

    @Test
    fun `a metadata refresh re-projects the selection`() = runTest {
        val item = lookupItem("a.txt")
        val refreshed = item.copy(canWrite = true)
        val controller = pruningController()
        controller.set(setOf(item))

        controller.pruneAgainst(listing(refreshed))

        controller.selectedItems.value shouldBe setOf(refreshed)
    }

    @Test
    fun `a still loading listing never prunes`() = runTest {
        val first = lookupItem("a.txt")
        val second = lookupItem("b.txt")
        val controller = pruningController()
        controller.set(setOf(first, second))

        controller.pruneAgainst(listing(first, loading = true))
        controller.selectedItems.value shouldBe setOf(first, second)

        controller.pruneAgainst(listing(loading = true))
        controller.selectedItems.value shouldBe setOf(first, second)
    }

    @Test
    fun `a listing of another location never prunes`() = runTest {
        val item = lookupItem("a.txt")
        val controller = pruningController()
        controller.set(setOf(item))

        controller.pruneAgainst(listing(path = "/tmp/somewhere-else"))

        controller.selectedItems.value shouldBe setOf(item)
    }

    @Test
    fun `items hidden by a filter stay selected`() = runTest {
        // Filtering only shortens the displayed list; pruning runs against the raw listing, which
        // still holds the item.
        val hidden = lookupItem("a.txt")
        val shown = lookupItem("b.txt")
        val controller = pruningController()
        controller.set(setOf(hidden))

        controller.pruneAgainst(listing(hidden, shown))

        controller.selectedItems.value shouldBe setOf(hidden)
    }

    companion object {
        private const val LISTING_PATH = "/tmp/selection-test"
    }
}
