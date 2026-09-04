package eu.darken.butler.workspace.ui.manager

/**
 * Which subset of tabs the manager grid shows. Facets are mutually exclusive: several of the
 * combinations an AND model would allow are provably empty, because a tab that can be paused has
 * no operations, no attention items and no unsaved changes, and a paused stand-in cannot acquire
 * any of them while it is down.
 */
enum class WorkspaceManagerFilter {
    OPERATIONS,
    ATTENTION,
    PAUSED,
    UNSAVED,
}
