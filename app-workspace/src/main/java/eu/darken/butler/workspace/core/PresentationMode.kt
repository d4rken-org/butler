package eu.darken.butler.workspace.core

/**
 * Defines how a workspace is presented in the UI
 */
enum class PresentationMode {
    /**
     * Normal workspace rendered in HorizontalPager as a tab
     * User can swipe between workspaces
     */
    TAB,

    /**
     * Workspace rendered as full-screen modal dialog overlay
     * User cannot interact with background until modal is dismissed
     * Typically used for picker/selection workflows
     */
    MODAL,
}
