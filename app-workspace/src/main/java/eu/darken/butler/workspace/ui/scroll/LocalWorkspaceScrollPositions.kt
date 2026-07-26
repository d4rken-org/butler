package eu.darken.butler.workspace.ui.scroll

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The registry backing [rememberWorkspaceLazyListState] and friends.
 *
 * The default is a detached instance so previews, screenshot tests and any other composition that
 * renders a page without the app graph work unprovided - and, more importantly, cannot read or
 * clobber the live positions.
 */
val LocalWorkspaceScrollPositions = staticCompositionLocalOf { WorkspaceScrollPositions() }
