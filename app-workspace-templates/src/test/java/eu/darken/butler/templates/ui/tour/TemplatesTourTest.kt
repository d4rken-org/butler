package eu.darken.butler.templates.ui.tour

import eu.darken.butler.workspace.ui.tour.WorkspaceTourTargets
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class TemplatesTourTest : BaseTest() {

    private val definition = TemplatesTour.definition(prepareFirstTemplate = {}, ownerKey = "pane")

    @Test
    fun `the tour id is stable`() {
        TemplatesTour.id.raw shouldBe "tour.templates.picker"
        definition.id shouldBe TemplatesTour.id
    }

    @Test
    fun `the picker step comes first, the Butler button second`() {
        definition.steps.map { it.stepId } shouldBe listOf("pickTool", "butlerButton")
        definition.steps.map { it.targetId } shouldBe listOf(
            TemplatesTour.FIRST_TEMPLATE_TARGET,
            WorkspaceTourTargets.BUTLER_BUTTON,
        )
    }

    @Test
    fun `the first step carries a prepareTarget hook`() {
        // Without it the first template card may not be composed at all and the step grace-skips.
        (definition.steps.first().prepareTarget != null) shouldBe true
        definition.steps.last().prepareTarget shouldBe null
    }

    @Test
    fun `click protection is on`() {
        definition.clickProtection shouldBe true
    }
}
