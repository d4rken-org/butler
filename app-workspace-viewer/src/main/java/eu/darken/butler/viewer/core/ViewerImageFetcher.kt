package eu.darken.butler.viewer.core

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import okio.FileSystem
import okio.buffer
import okio.source
import javax.inject.Inject

/**
 * Coil model for the viewer's own image loads.
 *
 * Deliberately NOT served by `PathPreviewFetcher`: that one takes an `APathLookup`, clamps to a
 * thumbnail budget and returns a generic icon for every `ArchivePath` so previews can never trigger
 * implicit decompression. In the viewer decompression is exactly what the user asked for, there is
 * no budget, and a failure must surface instead of silently becoming an icon.
 */
data class ViewerImageRequest(
    val source: ViewerSource,
)

class ViewerImageKeyer @Inject constructor() : Keyer<ViewerImageRequest> {
    // ViewerSource.cacheKey, not the path: streamed content has no path, and its key folds in the
    // arrival so two shares of one provider URI never serve each other's bytes.
    override fun key(data: ViewerImageRequest, options: Options): String = "viewer:${data.source.cacheKey}"
}

class ViewerImageFetcher(
    private val contentReader: ViewerContentReader,
    private val data: ViewerImageRequest,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        log(TAG) { "fetch(): ${data.source}" }
        // Hand-over: Coil reads the source after this returns, so it cannot be scope-bound.
        val stream = contentReader.openStreamForHandover(data.source)
        return SourceFetchResult(
            source = ImageSource(
                source = stream.source().buffer(),
                fileSystem = FileSystem.SYSTEM,
            ),
            // Declared type, not the file name: shared content often arrives without an extension.
            mimeType = data.source.mime.rawType,
            dataSource = DataSource.DISK,
        )
    }

    class Factory @Inject constructor(
        private val contentReader: ViewerContentReader,
    ) : Fetcher.Factory<ViewerImageRequest> {

        override fun create(
            data: ViewerImageRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ViewerImageFetcher(contentReader, data)
    }

    companion object {
        private val TAG = logTag("Viewer", "ImageFetcher")
    }
}
