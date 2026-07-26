package eu.darken.butler.explorer.ui.explorer

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
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
        navigate: (ExplorerItem) -> Unit = {},
    ) = ExplorerSelectionController(
        pickerConfig = { config },
        workspace = { workspace },
        selectableItems = { selectableItems },
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
}
