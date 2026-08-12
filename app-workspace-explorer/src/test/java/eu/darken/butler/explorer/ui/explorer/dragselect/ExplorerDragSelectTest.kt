package eu.darken.butler.explorer.ui.explorer.dragselect

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.dnd.ExplorerDragPayloadFactory
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerDragSelectTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val directoryPath = LocalPath.build("/storage/emulated/0/DCIM")

    private fun pickerConfig(selection: PickerConfig.Selection) = PickerConfig(
        callerWorkspaceId = Workspace.Id(),
        selection = selection,
    )

    private fun file(name: String) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = directoryPath.child(name),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("image/jpeg"),
    )

    private fun state(
        items: List<ExplorerItem>? = emptyList(),
        selectable: Set<ExplorerItem> = items.orEmpty().toSet(),
        selected: Set<ExplorerItem> = emptySet(),
        disabled: Set<ExplorerItem> = emptySet(),
        pickerConfig: PickerConfig? = null,
    ) = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(
            info = ExplorerLocation.Directory.Info(isWritable = true),
            path = directoryPath,
        ),
        items = items,
        selectionState = ExplorerSelectionState(selectableItems = selectable, selectedItems = selected),
        disabledItems = disabled,
        pickerConfig = pickerConfig,
    )

    /** The very factory the page hands down in a multi-pane layout. */
    private fun payloadFactory(
        state: ExplorerWorkspaceViewModel.State,
    ): (ExplorerItem) -> WorkspaceDragPayload? = { pressed ->
        ExplorerDragPayloadFactory.build(state, workspaceId, pressed)
    }

    @Test
    fun `keys are the eligible items in display order`() {
        val first = file("a.jpg")
        val second = file("b.jpg")
        val notSelectable = file("c.jpg")
        val disabled = file("d.jpg")
        val state = state(
            items = listOf(first, notSelectable, second, disabled),
            selectable = setOf(first, second, disabled),
            disabled = setOf(disabled),
        )

        explorerDragSelectKeys(state) shouldContainExactly listOf(first.id, second.id)
    }

    @Test
    fun `there are no keys while the listing is still loading`() {
        explorerDragSelectKeys(state(items = null)) shouldContainExactly emptyList()
    }

    @Test
    fun `a single-select picker never claims the long press`() {
        val item = file("a.jpg")
        val picker = pickerConfig(PickerConfig.Selection.FileSingle)

        explorerDragSelectClaims(state(items = listOf(item), pickerConfig = picker), item.id, null) shouldBe false
    }

    @Test
    fun `a multi-select picker claims the long press`() {
        val item = file("a.jpg")
        val picker = pickerConfig(PickerConfig.Selection.FileMulti)

        explorerDragSelectClaims(state(items = listOf(item), pickerConfig = picker), item.id, null) shouldBe true
    }

    @Test
    fun `without an active selection drag-select claims even in a multi-pane layout`() {
        val item = file("a.jpg")
        val state = state(items = listOf(item))

        explorerDragSelectClaims(state, item.id, payloadFactory(state)) shouldBe true
    }

    @Test
    fun `a draggable item in selection mode leaves the long press to the platform drag`() {
        val item = file("a.jpg")
        val state = state(items = listOf(item), selected = setOf(item))

        explorerDragSelectClaims(state, item.id, payloadFactory(state)) shouldBe false
    }

    @Test
    fun `an item the payload factory refuses is still claimed in selection mode`() {
        // Storage volumes and other non-lookup items never produce a payload - without evaluating
        // the factory they would end up owned by neither gesture.
        val selected = file("a.jpg")
        val peek = ExplorerItem.Peek(directoryPath.child("peeked.jpg"))
        val state = state(items = listOf(selected, peek), selected = setOf(selected))

        explorerDragSelectClaims(state, peek.id, payloadFactory(state)) shouldBe true
    }

    @Test
    fun `a single pane has no payload factory and always claims`() {
        val item = file("a.jpg")
        val state = state(items = listOf(item), selected = setOf(item))

        explorerDragSelectClaims(state, item.id, null) shouldBe true
    }

    @Test
    fun `ids resolve against the listing`() {
        val first = file("a.jpg")
        val second = file("b.jpg")
        val state = state(items = listOf(first, second))

        explorerDragSelectItems(state, setOf(first.id, second.id)) shouldBe setOf(first, second)
    }

    @Test
    fun `a selected item the filter hides survives the resolution`() {
        val visible = file("a.jpg")
        val hidden = file("hidden.jpg")
        val state = state(items = listOf(visible), selected = setOf(hidden))

        explorerDragSelectItems(state, setOf(visible.id, hidden.id)) shouldBe setOf(visible, hidden)
    }

    @Test
    fun `ids that resolve to nothing are dropped`() {
        val visible = file("a.jpg")
        val state = state(items = listOf(visible))

        explorerDragSelectItems(state, setOf(visible.id, "gone")) shouldBe setOf(visible)
    }
}
