package eu.darken.butler.workspace.core.preview

/**
 * Preview data for workspace tabs in the TabManager.
 *
 * Each workspace type provides type-specific structured data (paths, items, text snippets)
 * for text-based fallback previews.
 *
 * Visual previews are captured as PNG files in app cache and loaded via Coil using
 * [WorkspacePreviewModel]. The [WorkspacePreviewManager] handles automatic capture
 * and cleanup based on workspace lifecycle events.
 */
sealed interface PreviewData