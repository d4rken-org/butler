package eu.darken.butler.workspace.ui.manager.preview

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import eu.darken.butler.workspace.core.preview.WorkspacePreviewRepo
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import javax.inject.Inject

/**
 * Coil Fetcher for loading workspace preview images from cache.
 *
 * This fetcher resolves [WorkspacePreviewModel] to the cached PNG file
 * managed by [WorkspacePreviewRepo]. If no cached preview exists, the
 * fetch fails gracefully allowing Coil placeholder/error handling.
 */
class WorkspacePreviewFetcher @Inject constructor(
    private val previewRepo: WorkspacePreviewRepo,
    private val data: WorkspacePreviewModel,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        log(TAG) { "Fetching preview for workspace ${data.workspaceId.shortTag}" }

        val cachedFile = previewRepo.getCachedPreview(data.workspaceId)

        return if (cachedFile != null && cachedFile.exists()) {
            log(TAG) { "Found cached preview for ${data.workspaceId.shortTag}: ${cachedFile.length()} bytes" }
            val path = cachedFile.toOkioPath()
            val source = FileSystem.SYSTEM.source(path).buffer()
            SourceFetchResult(
                source = ImageSource(
                    source = source,
                    fileSystem = FileSystem.SYSTEM,
                ),
                mimeType = "image/png",
                dataSource = DataSource.DISK
            )
        } else {
            log(TAG) { "No cached preview for ${data.workspaceId.shortTag}, returning null" }
            null // Let Coil show placeholder/error
        }
    }

    class Factory @Inject constructor(
        private val previewRepo: WorkspacePreviewRepo,
    ) : Fetcher.Factory<WorkspacePreviewModel> {

        override fun create(
            data: WorkspacePreviewModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return WorkspacePreviewFetcher(
                previewRepo = previewRepo,
                data = data,
                options = options,
            )
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "PreviewFetcher")
    }
}
