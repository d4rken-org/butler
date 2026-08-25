package eu.darken.butler.workspace.ui.manager.preview

import android.graphics.Bitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.darken.butler.common.coil.use
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.main.ui.MainActivity
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import okio.Buffer
import okio.FileSystem
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Coil Fetcher for capturing workspace preview images on-demand.
 *
 * This fetcher captures workspace UI in real-time when requested by Coil.
 * It attempts to use the Activity's ViewModelStore for exact state capture,
 * falling back to temporary ViewModels if no Activity context is available.
 *
 * The captured bitmap is encoded to PNG format and cached to disk.
 * Subsequent requests check the disk cache first, avoiding expensive re-renders.
 */
@OptIn(ExperimentalCoilApi::class)
class WorkspacePreviewFetcher @Inject constructor(
    private val workspaceRepo: WorkspaceRepo,
    private val captureService: WorkspacePreviewCaptureService,
    private val keyer: WorkspacePreviewKeyer,
    private val diskCache: Lazy<DiskCache?>,
    private val data: WorkspacePreviewModel,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        log(TAG) { "Fetching preview for workspace ${data.workspaceId.shortTag}" }

        // Generate cache key using keyer
        val cacheKey = keyer.key(data, options)

        // Step 1: Check disk cache first
        diskCache.value?.openSnapshot(cacheKey)?.use { snapshot ->
            log(TAG) { "Disk cache HIT for ${data.workspaceId.shortTag}" }
            val cachedBytes = diskCache.value!!.fileSystem.read(snapshot.data) {
                readByteArray()
            }
            val bufferedSource = Buffer().write(cachedBytes)
            return SourceFetchResult(
                source = ImageSource(
                    source = bufferedSource,
                    fileSystem = FileSystem.SYSTEM,
                ),
                mimeType = "image/webp",
                dataSource = DataSource.DISK
            )
        }

        // Step 2: Disk cache miss - generate preview
        log(TAG) { "Disk cache MISS for ${data.workspaceId.shortTag} - generating preview" }

        val workspaceInfo = workspaceRepo.peekInfos().find { it.id == data.workspaceId }

        if (workspaceInfo == null) {
            log(TAG, WARN) { "Workspace ${data.workspaceId.shortTag} not found in repo" }
            return null
        }

        // A paused workspace has no instance to render yet - capturing it would load the very
        // workspace the pause is keeping asleep. Fall back to the static mock preview.
        if (workspaceInfo.isPaused) {
            log(TAG) { "Workspace ${data.workspaceId.shortTag} is paused, skipping capture" }
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
            size = DpSize(
                width = 360.dp,
                height = 640.dp,
            ),
            captureContext = mainActivity,
            viewmodelStoreOwner = mainActivity,
        ) ?: return null

        log(TAG) { "Successfully captured preview for ${data.workspaceId.shortTag}" }

        if (options.diskCachePolicy.writeEnabled) {
            val webpBytes = ByteArrayOutputStream().use { stream ->
                bitmap.compress(
                    @Suppress("NewApi")
                    if (hasApiLevel(30)) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG,
                    25,
                    stream
                )
                stream.toByteArray()
            }
            diskCache.value?.openEditor(cacheKey)?.use { editor ->
                diskCache.value!!.fileSystem.write(editor.data) {
                    write(webpBytes)
                }
                log(TAG) { "Wrote preview to disk cache for ${data.workspaceId.shortTag}" }
            }
        } else {
            log(TAG, WARN) { "Disk cache write is disabled for ${data.workspaceId.shortTag}" }
        }

        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = false,
            dataSource = DataSource.NETWORK
        )
    }

    class Factory @Inject constructor(
        private val workspaceRepo: WorkspaceRepo,
        private val captureService: WorkspacePreviewCaptureService,
        private val keyer: WorkspacePreviewKeyer,
    ) : Fetcher.Factory<WorkspacePreviewModel> {

        override fun create(
            data: WorkspacePreviewModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return WorkspacePreviewFetcher(
                workspaceRepo = workspaceRepo,
                captureService = captureService,
                keyer = keyer,
                diskCache = lazy { imageLoader.diskCache },
                data = data,
                options = options,
            )
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "Preview", "Fetcher")
    }
}
