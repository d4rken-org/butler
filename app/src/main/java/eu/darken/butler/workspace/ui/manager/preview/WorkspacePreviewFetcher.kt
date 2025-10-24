package eu.darken.butler.workspace.ui.manager.preview

import androidx.compose.ui.geometry.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.main.ui.MainActivity
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Coil Fetcher for capturing workspace preview images on-demand.
 *
 * This fetcher captures workspace UI in real-time when requested by Coil.
 * It attempts to use the Activity's ViewModelStore for exact state capture,
 * falling back to temporary ViewModels if no Activity context is available.
 *
 * The bitmap is returned directly to Coil, which handles all caching automatically.
 */
class WorkspacePreviewFetcher @Inject constructor(
    private val workspaceRepo: WorkspaceRepo,
    private val captureService: WorkspacePreviewCaptureService,
    private val data: WorkspacePreviewModel,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        log(TAG) { "Fetching preview for workspace ${data.workspaceId.shortTag}" }

        val workspaceInfo = workspaceRepo.state.first().infos.find { it.id == data.workspaceId }

        if (workspaceInfo == null) {
            log(TAG, WARN) { "Workspace ${data.workspaceId.shortTag} not found in repo" }
            return null
        }

        // Try to extract ViewModelStoreOwner from Coil's context
        val mainActivity = (options.context as? MainActivity)

        if (mainActivity == null) {
            log(TAG, WARN) { "No MainActivity available via context" }
            return null
        }

        val bitmap = captureService.captureWorkspace(
            workspaceId = data.workspaceId,
            workspaceType = workspaceInfo.type,
            size = Size(
                width = 800f,
                height = 1200f,
            ),
            captureContext = mainActivity,
            viewmodelStoreOwner = mainActivity,
        ) ?: return null

        log(TAG) { "Successfully captured preview for ${data.workspaceId.shortTag}" }

        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = false,
            dataSource = DataSource.MEMORY
        )
    }

    class Factory @Inject constructor(
        private val workspaceRepo: WorkspaceRepo,
        private val captureService: WorkspacePreviewCaptureService,
    ) : Fetcher.Factory<WorkspacePreviewModel> {

        override fun create(
            data: WorkspacePreviewModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return WorkspacePreviewFetcher(
                workspaceRepo = workspaceRepo,
                captureService = captureService,
                data = data,
                options = options,
            )
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "PreviewFetcher")
    }
}
