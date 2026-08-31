package eu.darken.butler.workspace.ui.manager.tour

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The tour id is persisted as "completed", and the three target ids have to match the ones the
 * manager's FAB and first card register — a drift in either silently turns a step into a grace-skip
 * or replays the whole tour.
 */
class WorkspaceManagerTourTest : BaseTest() {

    private fun definition(
        prepareAddTab: suspend () -> Unit = {},
        prepareFirstCard: suspend () -> Unit = {},
    ) = WorkspaceManagerTour.definition(
        prepareAddTab = prepareAddTab,
        prepareFirstCard = prepareFirstCard,
    )

    @Test
    fun `the tour id is stable`() {
        WorkspaceManagerTour.id.raw shouldBe "tour.workspace.manager"
        definition().id shouldBe WorkspaceManagerTour.id
    }

    @Test
    fun `the steps anchor on the add-tab button and both card halves in order`() {
        definition().steps.map { it.stepId to it.targetId } shouldBe listOf(
            "addTab" to WorkspaceManagerTour.ADD_TAB_TARGET,
            "reorder" to WorkspaceManagerTour.REORDER_TARGET,
            "select" to WorkspaceManagerTour.SELECT_TARGET,
        )
        WorkspaceManagerTour.ADD_TAB_TARGET shouldBe "workspaceManager.addTab"
        WorkspaceManagerTour.REORDER_TARGET shouldBe "workspaceManager.cardHeader"
        WorkspaceManagerTour.SELECT_TARGET shouldBe "workspaceManager.cardPreview"
    }

    @Test
    fun `every step prepares its own target`() {
        definition().steps.forEach { it.prepareTarget shouldNotBe null }
    }

    @Test
    fun `the add-tab step scrolls back to the top and both card steps to the first card`() = runTest {
        val calls = mutableListOf<String>()
        val steps = definition(
            prepareAddTab = { calls += "addTab" },
            prepareFirstCard = { calls += "firstCard" },
        ).steps
        steps.forEach { it.prepareTarget?.invoke() }
        calls shouldBe listOf("addTab", "firstCard", "firstCard")
    }

    @Test
    fun `every step carries a title and a body`() {
        definition().steps.forEach {
            it.title shouldNotBe null
            it.body shouldNotBe null
        }
    }

    @Test
    fun `click protection is on so the highlighted button cannot be tapped through the scrim`() {
        definition().clickProtection shouldBe true
    }
}
