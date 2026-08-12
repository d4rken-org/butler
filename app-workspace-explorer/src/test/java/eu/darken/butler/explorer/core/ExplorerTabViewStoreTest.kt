package eu.darken.butler.explorer.core

import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerTabViewStoreTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private val viewPrefs = WorkspaceViewPrefs()
    private val store = ExplorerTabViewStore(viewPrefs, json)

    private val id = Workspace.Id()

    private val logs = mutableListOf<Pair<Logging.Priority, String>>()
    private val logCapture = object : Logging.Logger {
        override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
            logs.add(priority to message)
        }
    }

    @BeforeEach
    fun installLogCapture() = Logging.install(logCapture)

    @AfterEach
    fun removeLogCapture() = Logging.remove(logCapture)

    private fun warnings() = logs.filter { it.first == Logging.Priority.WARN }.map { it.second }

    private fun storedSlot(slot: String) = viewPrefs.snapshot()[id]?.get(slot)

    /**
     * The payloads the workspace layer stores opaquely, written out by hand.
     *
     * Do NOT regenerate these from the models: a fixture produced by the code under test moves with
     * every rename and would happily agree with a break that orphans every tab's saved state.
     */
    private val goldenFilterPayload = """
        {"include":"*.md","exclude":"tmp","type":"files"}
    """.trimIndent()

    private val goldenFilter = FilterState(
        includePattern = "*.md",
        excludePattern = "tmp",
        fileTypeFilter = FileTypeFilter.FILES_ONLY,
    )

    private val goldenStylePayload = """
        {"type":"grid","size":"large"}
    """.trimIndent()

    private val goldenStyle = ExplorerViewStyle.Grid(size = ExplorerViewStyle.Grid.GridSize.LARGE)

    @Test
    fun `the stored filter payload shape is the wire contract`() {
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_FILTER) { json.parseToJsonElement(goldenFilterPayload) }

        store.currentFilter(id) shouldBe goldenFilter
    }

    @Test
    fun `filters are written back in the same shape`() {
        store.setFilter(id, goldenFilter)

        storedSlot(ExplorerTabViewStore.SLOT_FILTER) shouldBe json.parseToJsonElement(goldenFilterPayload)
    }

    @Test
    fun `each file type filter has its own wire name`() {
        listOf(
            FileTypeFilter.ALL to "all",
            FileTypeFilter.FILES_ONLY to "files",
            FileTypeFilter.FOLDERS_ONLY to "folders",
        ).forEach { (filter, wireName) ->
            val payload = """{"include":"x","exclude":"","type":"$wireName"}"""
            viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_FILTER) { json.parseToJsonElement(payload) }

            store.currentFilter(id).fileTypeFilter shouldBe filter
        }
    }

    @Test
    fun `missing filter fields decode to their defaults`() {
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_FILTER) { json.parseToJsonElement("""{"exclude":"tmp"}""") }

        store.currentFilter(id) shouldBe FilterState(excludePattern = "tmp")
    }

    @Test
    fun `an absent slot reads as the default filter`() {
        store.currentFilter(id) shouldBe FilterState()
    }

    @Test
    fun `a filter reset removes the slot`() {
        store.setFilter(id, goldenFilter)
        storedSlot(ExplorerTabViewStore.SLOT_FILTER) shouldBe json.parseToJsonElement(goldenFilterPayload)

        store.setFilter(id, FilterState())

        storedSlot(ExplorerTabViewStore.SLOT_FILTER) shouldBe null
        store.currentFilter(id) shouldBe FilterState()
    }

    @Test
    fun `the stored view style payload shape is the wire contract`() {
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_VIEWSTYLE) { json.parseToJsonElement(goldenStylePayload) }

        store.currentViewStyle(id) shouldBe goldenStyle
    }

    @Test
    fun `the view style is written back in the same shape`() {
        store.setViewStyle(id, goldenStyle)

        storedSlot(ExplorerTabViewStore.SLOT_VIEWSTYLE) shouldBe json.parseToJsonElement(goldenStylePayload)
    }

    @Test
    fun `an absent slot has no view style`() {
        store.currentViewStyle(id) shouldBe null
    }

    /** A restored tab keeps the style it was closed with, whatever the current global default is. */
    @Test
    fun `ensuring a view style does not clobber a restored one`() {
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_VIEWSTYLE) { json.parseToJsonElement(goldenStylePayload) }

        store.ensureViewStyle(id, ExplorerViewStyle.List())

        store.currentViewStyle(id) shouldBe goldenStyle
    }

    @Test
    fun `ensuring a view style materializes it when the tab has none`() {
        store.ensureViewStyle(id, goldenStyle)

        store.currentViewStyle(id) shouldBe goldenStyle
    }

    @Test
    fun `setting a view style overwrites the stored one`() {
        store.setViewStyle(id, goldenStyle)

        store.setViewStyle(id, ExplorerViewStyle.List())

        store.currentViewStyle(id) shouldBe ExplorerViewStyle.List()
    }

    @Test
    fun `a malformed filter payload degrades to the default and is logged`() {
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_FILTER) { JsonPrimitive("nope") }

        store.currentFilter(id) shouldBe FilterState()

        warnings().size shouldBe 1
        warnings().single().contains("DISCARDED") shouldBe true
    }

    @Test
    fun `a malformed view style payload degrades to none and is logged`() {
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_VIEWSTYLE) { JsonPrimitive("nope") }

        store.currentViewStyle(id) shouldBe null

        warnings().size shouldBe 1
        warnings().single().contains("IGNORED") shouldBe true
    }

    @Test
    fun `a malformed slot leaves its sibling readable`() {
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_FILTER) { JsonPrimitive("nope") }
        store.setViewStyle(id, goldenStyle)

        store.currentFilter(id) shouldBe FilterState()
        store.currentViewStyle(id) shouldBe goldenStyle
    }

    /** Unreadable payloads stay put until the tab writes that slot again - then they are gone. */
    @Test
    fun `a malformed payload is retained until the next write`() {
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_FILTER) { JsonPrimitive("nope") }
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_VIEWSTYLE) { JsonPrimitive("nope") }

        store.currentFilter(id) shouldBe FilterState()
        storedSlot(ExplorerTabViewStore.SLOT_FILTER) shouldBe JsonPrimitive("nope")
        store.ensureViewStyle(id, goldenStyle)
        storedSlot(ExplorerTabViewStore.SLOT_VIEWSTYLE) shouldBe JsonPrimitive("nope")

        store.setFilter(id, goldenFilter)
        store.setViewStyle(id, goldenStyle)

        store.currentFilter(id) shouldBe goldenFilter
        store.currentViewStyle(id) shouldBe goldenStyle
    }

    @Test
    fun `a reset clears an unreadable filter payload`() {
        viewPrefs.mutateSlot(id, ExplorerTabViewStore.SLOT_FILTER) { JsonPrimitive("nope") }

        store.setFilter(id, FilterState())

        storedSlot(ExplorerTabViewStore.SLOT_FILTER) shouldBe null
    }
}
