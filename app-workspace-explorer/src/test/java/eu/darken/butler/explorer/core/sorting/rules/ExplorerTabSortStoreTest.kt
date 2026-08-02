package eu.darken.butler.explorer.core.sorting.rules

import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerTabSortStoreTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private val viewPrefs = WorkspaceViewPrefs()
    private val store = ExplorerTabSortStore(viewPrefs, json)

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

    /**
     * The payload the workspace layer stores opaquely, written out by hand.
     *
     * Do NOT regenerate this from the model: a fixture produced by the code under test moves with
     * every rename and would happily agree with a break that orphans every tab's saved overrides.
     */
    private val goldenPayload = """
        {
          "default": {"mode": "SIZE", "reversed": true},
          "rules": {
            "local/sdcard/Download": {
              "settings": {"mode": "MODIFIED_AT", "reversed": false},
              "subtree": true,
              "path": "{\"type\":\"LOCAL\",\"file\":\"/sdcard/Download\"}"
            },
            "local/sdcard/Music": {
              "subtree": false,
              "path": "{\"type\":\"LOCAL\",\"file\":\"/sdcard/Music\"}"
            }
          }
        }
    """.trimIndent()

    private val goldenOverrides = TabSortOverrides(
        default = SortSettings(mode = SortSettings.Mode.SIZE, reversed = true),
        rules = mapOf(
            "local/sdcard/Download" to TabSortRule(
                settings = SortSettings(mode = SortSettings.Mode.MODIFIED_AT, reversed = false),
                subtree = true,
                path = """{"type":"LOCAL","file":"/sdcard/Download"}""",
            ),
            "local/sdcard/Music" to TabSortRule(
                settings = null,
                subtree = false,
                path = """{"type":"LOCAL","file":"/sdcard/Music"}""",
            ),
        ),
    )

    @Test
    fun `the stored payload shape is the wire contract`() = runTest {
        viewPrefs.mutateSlot(id, ExplorerTabSortStore.SLOT) { json.parseToJsonElement(goldenPayload) }

        store.overridesFor(id).first() shouldBe goldenOverrides
    }

    @Test
    fun `overrides are written back in the same shape`() = runTest {
        store.update(id) { goldenOverrides }

        val stored = viewPrefs.snapshot().getValue(id).getValue(ExplorerTabSortStore.SLOT)
        stored shouldBe json.parseToJsonElement(goldenPayload)
    }

    /** A marker rule is written with `settings` omitted, but an explicit null has to keep decoding. */
    @Test
    fun `an explicit null settings decodes as a marker`() = runTest {
        val payload = """
            {"rules":{"local/x":{"settings":null,"subtree":false,"path":"p"}}}
        """.trimIndent()
        viewPrefs.mutateSlot(id, ExplorerTabSortStore.SLOT) { json.parseToJsonElement(payload) }

        store.overridesFor(id).first().rules.getValue("local/x").settings shouldBe null
    }

    @Test
    fun `an absent slot reads as no overrides`() = runTest {
        store.overridesFor(id).first() shouldBe TabSortOverrides()
    }

    @Test
    fun `a malformed payload degrades to no overrides and is logged`() = runTest {
        viewPrefs.mutateSlot(id, ExplorerTabSortStore.SLOT) { JsonPrimitive("nope") }

        store.overridesFor(id).first() shouldBe TabSortOverrides()

        warnings().size shouldBe 1
        warnings().single().contains("DISCARDED") shouldBe true
    }

    @Test
    fun `an update that empties the overrides removes the slot`() = runTest {
        store.update(id) { it.copy(default = SortSettings(mode = SortSettings.Mode.SIZE)) }
        viewPrefs.snapshot().containsKey(id) shouldBe true

        store.update(id) { TabSortOverrides() }

        viewPrefs.snapshot() shouldBe emptyMap()
    }

    @Test
    fun `update sees the currently stored value`() = runTest {
        store.update(id) { it.copy(default = SortSettings(mode = SortSettings.Mode.SIZE)) }

        store.update(id) { current ->
            current.copy(
                rules = current.rules + ("local/x" to TabSortRule(settings = null, path = "p")),
            )
        }

        val result = store.overridesFor(id).first()
        result.default shouldBe SortSettings(mode = SortSettings.Mode.SIZE)
        result.rules.keys shouldBe setOf("local/x")
    }

    @Test
    fun `clear drops everything for the tab`() = runTest {
        store.update(id) { it.copy(default = SortSettings(mode = SortSettings.Mode.SIZE)) }

        store.clear(id)

        store.overridesFor(id).first() shouldBe TabSortOverrides()
        viewPrefs.snapshot() shouldBe emptyMap()
    }
}
