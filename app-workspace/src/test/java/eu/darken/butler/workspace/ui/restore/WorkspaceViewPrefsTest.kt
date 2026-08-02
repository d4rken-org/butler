package eu.darken.butler.workspace.ui.restore

import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The registry base is shared with shipped features (scroll positions, bar collapse), so the
 * additions this store needs are pinned here together with the behaviour they must not disturb.
 */
class WorkspaceViewPrefsTest : BaseTest() {

    private val prefs = WorkspaceViewPrefs()

    private fun payload(value: String): JsonElement = JsonPrimitive(value)

    @Test
    fun `mutateSlot writes a value that snapshot carries`() = runTest {
        val id = Workspace.Id()

        prefs.mutateSlot(id, "explorer.sort") { payload("a") }

        prefs.snapshot() shouldBe mapOf(id to mapOf("explorer.sort" to payload("a")))
    }

    @Test
    fun `observe emits the current value and every later mutation`() = runTest {
        val id = Workspace.Id()

        prefs.observe(id, "slot").first() shouldBe null

        prefs.mutateSlot(id, "slot") { payload("one") }
        prefs.observe(id, "slot").first() shouldBe payload("one")

        prefs.mutateSlot(id, "slot") { payload("two") }
        prefs.observe(id, "slot").first() shouldBe payload("two")
    }

    /**
     * The save-trigger counter is bumped only by records; an observer riding on it would never see a
     * restored value, which is exactly the case a tab reopening after process death depends on.
     */
    @Test
    fun `observe sees restore, forget and clear`() = runTest {
        val id = Workspace.Id()

        prefs.restore(mapOf(id to mapOf("slot" to payload("restored"))))
        prefs.observe(id, "slot").first() shouldBe payload("restored")

        prefs.forget(id)
        prefs.observe(id, "slot").first() shouldBe null

        prefs.mutateSlot(id, "slot") { payload("live") }
        prefs.observe(id, "slot").first() shouldBe payload("live")

        prefs.clear()
        prefs.observe(id, "slot").first() shouldBe null
    }

    @Test
    fun `deleting the last slot removes the workspace entry entirely`() = runTest {
        val id = Workspace.Id()
        prefs.mutateSlot(id, "a") { payload("1") }
        prefs.mutateSlot(id, "b") { payload("2") }

        prefs.mutateSlot(id, "a") { null }
        prefs.snapshot() shouldBe mapOf(id to mapOf("b" to payload("2")))

        prefs.mutateSlot(id, "b") { null }
        prefs.snapshot() shouldBe emptyMap()
    }

    @Test
    fun `restoring an empty slot map seeds nothing`() = runTest {
        val id = Workspace.Id()

        prefs.restore(mapOf(id to emptyMap()))

        prefs.snapshot() shouldBe emptyMap()
    }

    @Test
    fun `restore lets live entries win`() = runTest {
        val id = Workspace.Id()
        prefs.mutateSlot(id, "slot") { payload("live") }

        prefs.restore(mapOf(id to mapOf("slot" to payload("saved"), "other" to payload("seeded"))))

        prefs.snapshot() shouldBe mapOf(
            id to mapOf("slot" to payload("live"), "other" to payload("seeded")),
        )
    }

    /**
     * A stale lease is the reason mutateSlot exists: read-then-write would let a holder of the
     * pre-clear lease put the cleared value straight back. Invalidation is per slot, so unrelated
     * slots of the same workspace keep working.
     */
    @Test
    fun `a stale lease cannot resurrect a mutateSlot-deleted value`() = runTest {
        val id = Workspace.Id()
        // A fresh workspace is at generation 0, so these are the leases a holder would have taken
        val slotLease = SlotLease<JsonElement>(id, "slot", generation = 0L, saved = null)
        val siblingLease = SlotLease<JsonElement>(id, "sibling", generation = 0L, saved = null)
        prefs.record(slotLease, payload("live"))

        prefs.mutateSlot(id, "slot") { null }
        prefs.record(slotLease, payload("live"))

        prefs.snapshot() shouldBe emptyMap()

        prefs.record(siblingLease, payload("kept"))
        prefs.snapshot() shouldBe mapOf(id to mapOf("sibling" to payload("kept")))
    }

    /**
     * Observation must not touch access order: otherwise every unrelated write would re-promote the
     * observed slot and evict a genuinely newer one.
     */
    @Test
    fun `observing a slot does not protect it from LRU eviction`() = runTest {
        val id = Workspace.Id()
        val max = WorkspaceViewPrefs.MAX_SLOTS_PER_WORKSPACE
        (0 until max).forEach { index -> prefs.mutateSlot(id, "slot$index") { payload("v$index") } }

        // slot0 is the eldest and stays so despite being read on every mutation
        repeat(3) {
            prefs.observe(id, "slot0").first()
            prefs.mutateSlot(id, "slot${max - 1}") { payload("touched$it") }
        }
        prefs.mutateSlot(id, "overflow") { payload("new") }

        val slots = prefs.snapshot().getValue(id)
        slots.size shouldBe max
        slots.containsKey("slot0") shouldBe false
        slots.containsKey("overflow") shouldBe true
    }

    @Test
    fun `the save trigger ignores restore, forget and clear but not a mutation`() = runTest {
        val id = Workspace.Id()

        prefs.restore(mapOf(id to mapOf("slot" to payload("restored"))))
        prefs.changes.value shouldBe 0L

        prefs.mutateSlot(id, "slot") { payload("changed") }
        prefs.changes.value shouldBe 1L

        // A user-initiated clear of the payload has to reach the session row
        prefs.mutateSlot(id, "slot") { null }
        prefs.changes.value shouldBe 2L

        prefs.mutateSlot(id, "slot") { null }
        prefs.changes.value shouldBe 2L

        prefs.mutateSlot(id, "slot") { payload("back") }
        prefs.forget(id)
        prefs.clear()
        prefs.changes.value shouldBe 3L
    }
}
