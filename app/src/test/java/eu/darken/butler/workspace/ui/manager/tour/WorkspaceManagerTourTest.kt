package eu.darken.butler.workspace.ui.manager.tour

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The tour id is persisted as "completed", and the four target ids have to match the ones the
 * manager's FAB, first card and placeholder card register — a drift in either silently turns a step
 * into a grace-skip or replays the whole tour.
 */
class WorkspaceManagerTourTest : BaseTest() {

    private fun definition(
        prepareAddTab: suspend () -> Unit = {},
        prepareFirstCard: suspend () -> Unit = {},
        prepareNewTab: suspend () -> Unit = {},
    ) = WorkspaceManagerTour.definition(
        prepareAddTab = prepareAddTab,
        prepareFirstCard = prepareFirstCard,
        prepareNewTab = prepareNewTab,
    )

    @Test
    fun `the tour id is stable`() {
        WorkspaceManagerTour.id.raw shouldBe "tour.workspace.manager"
        definition().id shouldBe WorkspaceManagerTour.id
    }

    @Test
    fun `the steps anchor on the button, both card halves and the placeholder in order`() {
        definition().steps.map { it.stepId to it.targetId } shouldBe listOf(
            "addTab" to WorkspaceManagerTour.ADD_TAB_TARGET,
            "reorder" to WorkspaceManagerTour.REORDER_TARGET,
            "select" to WorkspaceManagerTour.SELECT_TARGET,
            "newTab" to WorkspaceManagerTour.NEW_TAB_TARGET,
        )
        WorkspaceManagerTour.ADD_TAB_TARGET shouldBe "workspaceManager.addTab"
        WorkspaceManagerTour.REORDER_TARGET shouldBe "workspaceManager.cardHeader"
        WorkspaceManagerTour.SELECT_TARGET shouldBe "workspaceManager.cardPreview"
        WorkspaceManagerTour.NEW_TAB_TARGET shouldBe "workspaceManager.newTabCard"
    }

    @Test
    fun `every step prepares its own target`() {
        definition().steps.forEach { it.prepareTarget shouldNotBe null }
    }

    @Test
    fun `the button step scrolls to the top, the card steps to the first card, the last to the end`() =
        runTest {
            val calls = mutableListOf<String>()
            val steps = definition(
                prepareAddTab = { calls += "addTab" },
                prepareFirstCard = { calls += "firstCard" },
                prepareNewTab = { calls += "newTab" },
            ).steps
            steps.forEach { it.prepareTarget?.invoke() }
            calls shouldBe listOf("addTab", "firstCard", "firstCard", "newTab")
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
