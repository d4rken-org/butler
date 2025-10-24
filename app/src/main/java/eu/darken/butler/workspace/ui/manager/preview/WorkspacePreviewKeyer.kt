package eu.darken.butler.workspace.ui.manager.preview

import coil3.key.Keyer
import coil3.request.Options
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import javax.inject.Inject

/**
 * Coil Keyer that generates cache keys for workspace preview images.
 *
 * Preview cache is managed by WorkspacePreviewRefreshManager, which invalidates
 * cached previews when workspaces gain focus.
 */
class WorkspacePreviewKeyer @Inject constructor() : Keyer<WorkspacePreviewModel> {
    override fun key(data: WorkspacePreviewModel, options: Options): String {
        return "workspace-preview-${data.workspaceId.id}"
    }
}
