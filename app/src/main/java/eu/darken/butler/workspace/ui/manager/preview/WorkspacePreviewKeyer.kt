package eu.darken.butler.workspace.ui.manager.preview

import coil3.key.Keyer
import coil3.request.Options
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import javax.inject.Inject

/**
 * Coil Keyer that generates cache keys for workspace preview images with time-based expiration.
 *
 * Workspace previews expire after 5 minutes to ensure they reflect recent state
 * while avoiding excessive re-captures for static workspaces.
 *
 * The cache key includes a time bucket that changes every 5 minutes, causing Coil
 * to treat the image as a new entry and trigger a fresh capture.
 */
class WorkspacePreviewKeyer @Inject constructor() : Keyer<WorkspacePreviewModel> {

    override fun key(data: WorkspacePreviewModel, options: Options): String {
        // Time bucket changes every 5 minutes (300 seconds)
        val timeBucket = System.currentTimeMillis() / (5 * 60 * 1000)
        val cacheKey = "workspace-preview-${data.workspaceId.id}-$timeBucket"

        log(TAG) { "Generated cache key for ${data.workspaceId.shortTag}: $cacheKey" }

        return cacheKey
    }

    companion object {
        private val TAG = logTag("Workspace", "PreviewKeyer")
    }
}
