package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The registry backing [rememberPaneFloatingBarStackState]'s collapse persistence.
 *
 * The default is a detached instance so previews, screenshot tests and any other composition that
 * renders a page without the app graph work unprovided - and, more importantly, cannot read or
 * clobber the live state.
 */
val LocalWorkspaceBarCollapseStates = staticCompositionLocalOf { WorkspaceBarCollapseStates() }
