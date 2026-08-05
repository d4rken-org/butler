package eu.darken.butler.workspace.ui.workspaces.tour

import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.tour.GuidedTour
import eu.darken.butler.common.compose.tour.TourDefinition
import eu.darken.butler.common.compose.tour.TourId
import eu.darken.butler.common.compose.tour.TourStep

/**
 * Points at the "create a tab" card on the empty workspaces surface.
 *
 * [CREATE_TAB_TARGET] is deliberately one id across both layouts (classic pager and adaptive
 * panes). A [TourDefinition] is immutable once started, so a layout-specific id would strand a
 * running tour on rotation or a panel-mode change: the old id's anchor unregisters, the host waits
 * out its grace window and completes the one-step tour — and because a step had rendered, that
 * completion is persisted. Uniqueness is instead guaranteed by only ever tagging one card.
 */
object FirstTabTour : GuidedTour {

    override val id: TourId = TourId("tour.workspaces.firstTab")

    const val CREATE_TAB_TARGET = "workspaces.createTab"

    /**
     * [prepareCreateTab] scrolls the create/add-tab card into the viewport before the step is
     * published. It is required, not optional: both empty-state surfaces wrap their content in a
     * `verticalScroll`, so in a compact or split-screen window, at large font scale, or in a short
     * adaptive pane the card sits below the viewport — `boundsInRoot()` clips it to nothing, no
     * anchor registers, and the single step grace-skips before the tour ever appears.
     */
    fun definition(prepareCreateTab: suspend () -> Unit): TourDefinition = TourDefinition(
        id = id,
        clickProtection = true,
        steps = listOf(
            TourStep(
                stepId = "createTab",
                targetId = CREATE_TAB_TARGET,
                title = R.string.tour_first_tab_title.toCaString(),
                body = R.string.tour_first_tab_body.toCaString(),
                prepareTarget = prepareCreateTab,
            ),
        ),
    )
}
