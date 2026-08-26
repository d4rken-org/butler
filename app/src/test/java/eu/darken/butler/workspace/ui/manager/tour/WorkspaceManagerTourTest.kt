package eu.darken.butler.workspace.ui.manager.tour

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The tour id is persisted as "completed", and the target id has to match the one the manager's FAB
 * registers — a drift in either silently turns the tour into a no-op or replays it.
 */
class WorkspaceManagerTourTest : BaseTest() {

    private val definition = WorkspaceManagerTour.definition

    @Test
    fun `the tour id is stable`() {
        WorkspaceManagerTour.id.raw shouldBe "tour.workspace.manager"
        definition.id shouldBe WorkspaceManagerTour.id
    }

    @Test
    fun `a single step anchors on the add-tab target`() {
        definition.steps.size shouldBe 1
        definition.steps.single().let {
            it.stepId shouldBe "addTab"
            it.targetId shouldBe WorkspaceManagerTour.ADD_TAB_TARGET
        }
        WorkspaceManagerTour.ADD_TAB_TARGET shouldBe "workspaceManager.addTab"
    }

    @Test
    fun `the step needs no prepareTarget because the overlay opens unscrolled`() {
        definition.steps.single().prepareTarget shouldBe null
    }

    @Test
    fun `click protection is on so the highlighted button cannot be tapped through the scrim`() {
        definition.clickProtection shouldBe true
    }
}
