package eu.darken.butler.workspace.ui.manager.preview

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import javax.inject.Inject

/**
 * Coil Interceptor that adds cache expiration metadata to workspace preview images.
 *
 * Workspace previews expire after 5 minutes to ensure they reflect recent state
 * while avoiding excessive re-captures for static workspaces.
 */
class WorkspacePreviewCacheInterceptor @Inject constructor() : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()

        // Only apply expiration to workspace preview requests
        if (result is SuccessResult && chain.request.data is WorkspacePreviewModel) {
            // Add max-age metadata (5 minutes = 300 seconds)
            // Cache key changes every 5 minutes → forces re-capture
            return result.copy(
                diskCacheKey = result.diskCacheKey?.let { key ->
                    "$key-${System.currentTimeMillis() / (5 * 60 * 1000)}"
                }
            )
        }

        return result
    }
}
