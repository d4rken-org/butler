package eu.darken.butler.workspace.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal that provides workspace focus state to composables.
 *
 * In adaptive multi-pane layouts, this indicates whether the current workspace pane
 * is the focused one. Components like text fields can use this to manage their
 * focus state appropriately (e.g., clearing focus when the workspace loses focus).
 *
 * Default value is `true` for backwards compatibility with single-pane layouts.
 */
val LocalWorkspaceFocused = compositionLocalOf { true }
