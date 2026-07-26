package eu.darken.butler.workspace.ui.scroll

import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class WorkspaceScrollPositionsTest : BaseTest() {

    private val registry = WorkspaceScrollPositions()

    @Test
    fun `record and read back`() {
        val id = Workspace.Id()
        val lease = registry.positionFor(id, "list")
        lease.saved shouldBe null

        registry.record(lease, WorkspaceScrollPosition(12, 34))

        registry.positionFor(id, "list").saved shouldBe WorkspaceScrollPosition(12, 34)
    }

    @Test
    fun `slots are independent`() {
        val id = Workspace.Id()
        registry.record(registry.positionFor(id, "list"), WorkspaceScrollPosition(1))
        registry.record(registry.positionFor(id, "grid"), WorkspaceScrollPosition(2))

        registry.positionFor(id, "list").saved shouldBe WorkspaceScrollPosition(1)
        registry.positionFor(id, "grid").saved shouldBe WorkspaceScrollPosition(2)
    }

    @Test
    fun `an unchanged position is not written`() {
        val id = Workspace.Id()
        val lease = registry.positionFor(id, "list")

        registry.record(lease, WorkspaceScrollPosition(5, 5))
        val afterFirst = registry.changes.value

        registry.record(lease, WorkspaceScrollPosition(5, 5))
        registry.changes.value shouldBe afterFirst

        registry.record(lease, WorkspaceScrollPosition(5, 6))
        registry.changes.value shouldNotBe afterFirst
    }

    @Test
    fun `slots are evicted least recently used`() {
        val id = Workspace.Id()
        val max = WorkspaceScrollPositions.MAX_SLOTS_PER_WORKSPACE
        (0 until max).forEach { index ->
            registry.record(registry.positionFor(id, "slot$index"), WorkspaceScrollPosition(index + 1))
        }

        // Touch the oldest slot so the second-oldest becomes the eviction candidate
        registry.positionFor(id, "slot0").saved shouldBe WorkspaceScrollPosition(1)
        registry.record(registry.positionFor(id, "overflow"), WorkspaceScrollPosition(99))

        registry.snapshot().getValue(id).size shouldBe max
        registry.positionFor(id, "slot0").saved shouldBe WorkspaceScrollPosition(1)
        registry.positionFor(id, "slot1").saved shouldBe null
        registry.positionFor(id, "overflow").saved shouldBe WorkspaceScrollPosition(99)
    }

    @Test
    fun `a stale lease cannot resurrect a forgotten workspace`() {
        val id = Workspace.Id()
        val lease = registry.positionFor(id, "list")
        registry.record(lease, WorkspaceScrollPosition(10))

        registry.forget(id)

        // The page is still being disposed and writes one last time
        registry.record(lease, WorkspaceScrollPosition(11))

        registry.snapshot() shouldBe emptyMap()
    }

    @Test
    fun `clear invalidates leases of workspaces that never recorded anything`() {
        val recorded = Workspace.Id()
        val untouched = Workspace.Id()
        val recordedLease = registry.positionFor(recorded, "list")
        val untouchedLease = registry.positionFor(untouched, "list")
        registry.record(recordedLease, WorkspaceScrollPosition(3))

        registry.clear()

        registry.record(recordedLease, WorkspaceScrollPosition(4))
        registry.record(untouchedLease, WorkspaceScrollPosition(5))

        registry.snapshot() shouldBe emptyMap()
    }

    @Test
    fun `a fresh lease works again after forget`() {
        val id = Workspace.Id()
        registry.record(registry.positionFor(id, "list"), WorkspaceScrollPosition(1))
        registry.forget(id)

        registry.record(registry.positionFor(id, "list"), WorkspaceScrollPosition(7))

        registry.positionFor(id, "list").saved shouldBe WorkspaceScrollPosition(7)
    }

    @Test
    fun `restore does not overwrite live slots`() {
        val id = Workspace.Id()
        registry.record(registry.positionFor(id, "list"), WorkspaceScrollPosition(42))

        registry.restore(
            mapOf(
                id to mapOf(
                    "list" to WorkspaceScrollPosition(1),
                    "grid" to WorkspaceScrollPosition(2),
                ),
            )
        )

        registry.positionFor(id, "list").saved shouldBe WorkspaceScrollPosition(42)
        registry.positionFor(id, "grid").saved shouldBe WorkspaceScrollPosition(2)
    }

    @Test
    fun `snapshot is a detached copy`() {
        val id = Workspace.Id()
        registry.record(registry.positionFor(id, "list"), WorkspaceScrollPosition(1))

        val snapshot = registry.snapshot()
        registry.record(registry.positionFor(id, "list"), WorkspaceScrollPosition(2))
        registry.record(registry.positionFor(id, "grid"), WorkspaceScrollPosition(3))

        snapshot.getValue(id) shouldBe mapOf("list" to WorkspaceScrollPosition(1))
    }
}
