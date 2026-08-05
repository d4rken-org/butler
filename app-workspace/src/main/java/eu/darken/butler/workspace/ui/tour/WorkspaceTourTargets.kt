package eu.darken.butler.workspace.ui.tour

/**
 * Guided-tour target ids for workspace chrome that lives outside the module defining the tour.
 *
 * Home is `app-workspace` because it is visible to both `:app` (navigation rail) and the workspace
 * implementation modules (e.g. the Templates page), which must not depend on `:app`.
 */
object WorkspaceTourTargets {

    /**
     * The Butler button - the tab manager entry point. Exactly one is rendered per screen: the
     * Templates page draws it in single-pane, the navigation rail in every multi-pane layout.
     */
    const val BUTLER_BUTTON = "workspace.butlerButton"
}
