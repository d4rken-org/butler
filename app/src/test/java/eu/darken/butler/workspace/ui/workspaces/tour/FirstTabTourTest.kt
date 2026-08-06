package eu.darken.butler.workspace.ui.workspaces.tour

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The tour id is persisted as "completed", and the target id is written in two places (classic and
 * adaptive empty content) — a drift in either silently turns the tour into a no-op or replays it.
 */
class FirstTabTourTest : BaseTest() {

    private val definition = FirstTabTour.definition(prepareCreateTab = {})

    @Test
    fun `the tour id is stable`() {
        FirstTabTour.id.raw shouldBe "tour.workspaces.firstTab"
        definition.id shouldBe FirstTabTour.id
    }

    @Test
    fun `a single step anchors on the one shared create-tab target`() {
        definition.steps.size shouldBe 1
        definition.steps.single().let {
            it.stepId shouldBe "createTab"
            it.targetId shouldBe FirstTabTour.CREATE_TAB_TARGET
        }
        FirstTabTour.CREATE_TAB_TARGET shouldBe "workspaces.createTab"
    }

    @Test
    fun `the single step carries a prepareTarget hook`() {
        // Without it the card stays below the fold on a short viewport, never registers an anchor
        // and the one-step tour grace-skips before it is ever seen.
        (definition.steps.single().prepareTarget != null) shouldBe true
    }

    @Test
    fun `click protection is on so the highlighted card cannot be tapped through the scrim`() {
        definition.clickProtection shouldBe true
    }
}
