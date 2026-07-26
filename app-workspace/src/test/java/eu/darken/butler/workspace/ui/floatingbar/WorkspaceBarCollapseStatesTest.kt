package eu.darken.butler.workspace.ui.floatingbar

import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class WorkspaceBarCollapseStatesTest : BaseTest() {

    private val registry = WorkspaceBarCollapseStates()

    @Test
    fun `record and read back`() {
        val id = Workspace.Id()
        val lease = registry.collapseFor(id, BarPosition.TOP)
        lease.saved shouldBe null

        registry.record(lease, 1f)

        registry.collapseFor(id, BarPosition.TOP).saved shouldBe 1f
    }

    @Test
    fun `the two stack positions are independent`() {
        val id = Workspace.Id()
        registry.record(registry.collapseFor(id, BarPosition.TOP), 1f)
        registry.record(registry.collapseFor(id, BarPosition.BOTTOM), 0f)

        registry.collapseFor(id, BarPosition.TOP).saved shouldBe 1f
        registry.collapseFor(id, BarPosition.BOTTOM).saved shouldBe 0f
    }

    @Test
    fun `an unchanged fraction is not written`() {
        val id = Workspace.Id()
        val lease = registry.collapseFor(id, BarPosition.TOP)

        registry.record(lease, 1f)
        val afterFirst = registry.changes.value

        registry.record(lease, 1f)
        registry.changes.value shouldBe afterFirst

        registry.record(lease, 0f)
        registry.changes.value shouldNotBe afterFirst
    }

    @Test
    fun `a stale lease cannot resurrect a forgotten workspace`() {
        val id = Workspace.Id()
        val lease = registry.collapseFor(id, BarPosition.TOP)
        registry.record(lease, 1f)

        registry.forget(id)

        // The page is still being disposed and writes one last time
        registry.record(lease, 1f)

        registry.snapshot() shouldBe emptyMap()
    }

    @Test
    fun `clear invalidates leases of workspaces that never recorded anything`() {
        val recorded = Workspace.Id()
        val untouched = Workspace.Id()
        val recordedLease = registry.collapseFor(recorded, BarPosition.TOP)
        val untouchedLease = registry.collapseFor(untouched, BarPosition.TOP)
        registry.record(recordedLease, 1f)

        registry.clear()

        registry.record(recordedLease, 1f)
        registry.record(untouchedLease, 1f)

        registry.snapshot() shouldBe emptyMap()
    }

    @Test
    fun `a fresh lease works again after forget`() {
        val id = Workspace.Id()
        registry.record(registry.collapseFor(id, BarPosition.TOP), 1f)
        registry.forget(id)

        registry.record(registry.collapseFor(id, BarPosition.TOP), 0.5f)

        registry.collapseFor(id, BarPosition.TOP).saved shouldBe 0.5f
    }

    @Test
    fun `restore does not overwrite live slots`() {
        val id = Workspace.Id()
        registry.record(registry.collapseFor(id, BarPosition.TOP), 0f)

        registry.restore(
            mapOf(
                id to mapOf(
                    BarPosition.TOP.name to 1f,
                    BarPosition.BOTTOM.name to 1f,
                ),
            )
        )

        registry.collapseFor(id, BarPosition.TOP).saved shouldBe 0f
        registry.collapseFor(id, BarPosition.BOTTOM).saved shouldBe 1f
    }

    @Test
    fun `snapshot is a detached copy keyed by position name`() {
        val id = Workspace.Id()
        registry.record(registry.collapseFor(id, BarPosition.TOP), 1f)

        val snapshot = registry.snapshot()
        registry.record(registry.collapseFor(id, BarPosition.BOTTOM), 1f)

        snapshot.getValue(id) shouldBe mapOf("TOP" to 1f)
    }
}
