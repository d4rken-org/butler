package eu.darken.butler.workspace.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.label

/**
 * Resolved workspace titles, for UI that names a workspace it does not host.
 *
 * The value is nullable and carries three distinct meanings:
 * - `null`: this composition host keeps no workspace registry, so nothing can be said about any
 *   workspace. Detached compositions and previews land here, as does the first frame before the
 *   workspace state has arrived.
 * - a map that does not contain the id: the workspace is gone.
 * - a map that contains the id: the title to render.
 *
 * An `emptyMap()` default would collapse the first two cases and make a live workspace read as
 * closed.
 */
val LocalWorkspaceTitles = compositionLocalOf<Map<Workspace.Id, String>?> { null }

/**
 * The label for [origin], or `null` when the caller should omit the field entirely.
 */
@Composable
internal fun originWorkspaceLabel(origin: Workspace.Id): String? {
    val titles = LocalWorkspaceTitles.current ?: return null
    return titles[origin] ?: stringResource(R.string.clipboard_info_workspace_closed)
}

/**
 * The name a workspace is referred to by outside its own chrome: the user's name for it when set,
 * otherwise the generic name of its type.
 */
val Workspace.Info.tabLabel: CaString
    get() = customTitle?.takeIf { it.isNotBlank() }?.toCaString() ?: type.label
