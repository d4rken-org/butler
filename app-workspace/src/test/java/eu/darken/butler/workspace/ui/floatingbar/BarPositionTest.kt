package eu.darken.butler.workspace.ui.floatingbar

import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * [BarPosition.persistedKey] is a wire value, not a label: it is the slot key of the session blob's
 * `barCollapse` map, and how [FloatingBarStackState.Saver] identifies a stack's position across
 * process death. Changing one here means every stored bar state under the old key is orphaned, so
 * the values are spelled out literally - a test that derived them from the enum would agree with any
 * rename.
 */
class BarPositionTest : BaseTest() {

    @Test
    fun `the persisted keys are pinned`() {
        BarPosition.TOP.persistedKey shouldBe "TOP"
        BarPosition.BOTTOM.persistedKey shouldBe "BOTTOM"

        BarPosition.entries.map { it.persistedKey } shouldBe listOf("TOP", "BOTTOM")
    }

    @Test
    fun `the collapse registry stores bars under the persisted key`() {
        val registry = WorkspaceBarCollapseStates()
        val id = Workspace.Id()

        registry.record(registry.collapseFor(id, BarPosition.TOP), mapOf("toolbar" to 1f))
        registry.record(registry.collapseFor(id, BarPosition.BOTTOM), mapOf("actions" to 0f))

        registry.snapshot().getValue(id) shouldBe mapOf(
            "TOP" to mapOf("toolbar" to 1f),
            "BOTTOM" to mapOf("actions" to 0f),
        )
    }
}
