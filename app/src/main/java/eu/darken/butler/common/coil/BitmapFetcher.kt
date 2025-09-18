package eu.darken.butler.common.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.darken.butler.common.MimeTypeTool
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.metadata.FileType
import javax.inject.Inject

class BitmapFetcher @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val mimeTypeTool: MimeTypeTool,
    private val data: Request,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val target = data.lookup
        if (target.fileType != FileType.FILE) throw IllegalArgumentException("Not a file: $data")
        if (target.size == 0L) throw IllegalArgumentException("Empty file: $data")

        val mimeType = mimeTypeTool.determineMimeType(data.lookup)

        val isValid = mimeType.startsWith("image")
        if (!isValid) throw UnsupportedOperationException("Unsupported mimetype: $mimeType")

        val handle = gatewaySwitch.file(target.lookedUp, readWrite = false)

        return SourceFetchResult(
            source = handle.toImageSource(),
            mimeType = mimeType,
            dataSource = DataSource.DISK
        )
    }

    class Factory @Inject constructor(
        private val gatewaySwitch: GatewaySwitch,
        private val mimeTypeTool: MimeTypeTool,
    ) : Fetcher.Factory<Request> {

        override fun create(
            data: Request,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = BitmapFetcher(gatewaySwitch, mimeTypeTool, data, options)
    }

    data class Request(
        val lookup: APathLookup<*>
    )
}

