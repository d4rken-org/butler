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

/**
 * CompositionLocal that provides a callback to request workspace focus.
 *
 * Nested components (like text fields) that consume click events should call this
 * to ensure their parent workspace becomes focused when tapped. Without this,
 * clicks on text fields don't propagate to the pane's click handler.
 *
 * Default is `null` - components should check before invoking.
 */
val LocalWorkspaceFocusRequest = compositionLocalOf<(() -> Unit)?> { null }
