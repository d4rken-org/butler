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
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.MimeInfo
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
    val path: APath<*>,
)

class ViewerImageKeyer @Inject constructor() : Keyer<ViewerImageRequest> {
    override fun key(data: ViewerImageRequest, options: Options): String = "viewer:${data.path.path}"
}

class ViewerImageFetcher(
    private val gatewaySwitch: GatewaySwitch,
    private val data: ViewerImageRequest,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        log(TAG) { "fetch(): ${data.path}" }
        val stream = gatewaySwitch.openInputStream(data.path)
        return SourceFetchResult(
            source = ImageSource(
                source = stream.source().buffer(),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = MimeInfo.fromFileName(data.path.name).rawType,
            dataSource = DataSource.DISK,
        )
    }

    class Factory @Inject constructor(
        private val gatewaySwitch: GatewaySwitch,
    ) : Fetcher.Factory<ViewerImageRequest> {

        override fun create(
            data: ViewerImageRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ViewerImageFetcher(gatewaySwitch, data)
    }

    companion object {
        private val TAG = logTag("Viewer", "ImageFetcher")
    }
}
