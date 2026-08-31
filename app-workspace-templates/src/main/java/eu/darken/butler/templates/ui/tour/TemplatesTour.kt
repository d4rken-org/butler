package eu.darken.butler.templates.ui.tour

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.tour.GuidedTour
import eu.darken.butler.common.compose.tour.TourDefinition
import eu.darken.butler.common.compose.tour.TourId
import eu.darken.butler.common.compose.tour.TourStep
import eu.darken.butler.templates.R
import eu.darken.butler.workspace.ui.tour.WorkspaceTourTargets

/**
 * Introduces the tab picker: what the template list is for, then where to manage tabs.
 *
 * The trigger is independent of any other tour — it fires on the first focused view of the picker,
 * whenever that happens — so the copy describes the picker rather than claiming the user just
 * created their first tab, which would be false for a restored Templates tab or one opened from
 * the tab manager.
 */
object TemplatesTour : GuidedTour {

    override val id: TourId = TourId("tour.templates.picker")

    const val FIRST_TEMPLATE_TARGET = "templates.firstTemplate"

    /**
     * [prepareFirstTemplate] scrolls the template list so the first card is composed before the
     * step is published. It is required, not optional: the template items sit after two other list
     * items and the list restores its saved scroll offset, so on a short window, at large font
     * scale, or on a restored tab that was scrolled, the card would not be composed at all — its
     * anchor never registers and the host grace-skips the step.
     *
     * Both steps always run: every layout renders exactly one Butler button (Templates page in
     * single-pane, navigation rail otherwise), so a conditional step list would be dead code.
     *
     * [ownerKey] names the pane that built this definition: the picker can be open in two panes at
     * once, and the keys are what keep one pane's session from adopting the other's prepare hooks.
     */
    fun definition(
        prepareFirstTemplate: suspend () -> Unit,
        ownerKey: String,
    ): TourDefinition = TourDefinition(
        id = id,
        ownerKey = ownerKey,
        clickProtection = true,
        steps = listOf(
            TourStep(
                stepId = "pickTool",
                targetId = FIRST_TEMPLATE_TARGET,
                title = R.string.tour_templates_picker_title.toCaString(),
                body = R.string.tour_templates_picker_body.toCaString(),
                prepareTarget = prepareFirstTemplate,
            ),
            TourStep(
                stepId = "butlerButton",
                targetId = WorkspaceTourTargets.BUTLER_BUTTON,
                title = R.string.tour_templates_butler_button_title.toCaString(),
                body = R.string.tour_templates_butler_button_body.toCaString(),
            ),
        ),
    )
}
