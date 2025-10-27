package eu.darken.butler.common.coil.fetchers

import android.content.Context
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.R
import eu.darken.butler.common.MimeTypeTool
import eu.darken.butler.common.coil.toImageSource
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.extension
import eu.darken.butler.common.files.iconRes
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.hashing.Hasher
import eu.darken.butler.common.hashing.hash
import javax.inject.Inject

class PathPreviewFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val mimeTypeTool: MimeTypeTool,
    private val textPreviewGenerator: TextPreviewGenerator,
    private val apkPreviewGenerator: ApkPreviewGenerator,
    private val data: APathLookup<*>,
    private val options: Options,
) : Fetcher {

    private fun isEasterEggPath(): Boolean {
        val segments = data.lookedUp.segments
        if (segments.size < 3) return false

        val hash1 = segments[segments.size - 1].lowercase().hash(Hasher.Type.SHA1).format()
        if (hash1 != "d6eed32bf5cb8c8d99b6f76ebe408c342d5377c2") return false

        val hash2 = segments[segments.size - 2].lowercase().hash(Hasher.Type.SHA1).format()
        if (hash2 != "e388d34fd3c0456122779e95f262c0d70198a168") return false
        log(TAG) { "X <3 Y Easter Egg :-)" }
        return true
    }

    private val easterEggIcon: FetchResult by lazy {
        ImageFetchResult(
            image = ContextCompat.getDrawable(options.context, R.drawable.ic_heart_24)!!.asImage(),
            isSampled = false,
            dataSource = DataSource.MEMORY
        )
    }

    private val fallbackIcon: FetchResult by lazy {
        ImageFetchResult(
            image = ContextCompat.getDrawable(options.context, data.fileType.iconRes)!!.asImage(),
            isSampled = false,
            dataSource = DataSource.MEMORY
        )
    }



    override suspend fun fetch(): FetchResult {
        if (isEasterEggPath()) return easterEggIcon

        if (data.fileType != FileType.FILE || data.size == 0L) return fallbackIcon

        val mimeType = mimeTypeTool.determineMimeType(data)

        return when {
            mimeType.startsWith("image") || mimeType.startsWith("video") -> {
                val handle = gatewaySwitch.file(data.lookedUp, readWrite = false)

                SourceFetchResult(
                    source = handle.toImageSource(),
                    mimeType = mimeType,
                    dataSource = DataSource.DISK
                )
            }

            mimeType == "application/octet-stream" && data.lookedUp.extension == "apk" && data.lookedUp is LocalPath -> {
                val bitmap = apkPreviewGenerator.generate(data.lookedUp as LocalPath)
                bitmap?.let {
                    ImageFetchResult(
                        image = it.asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK
                    )
                } ?: fallbackIcon
            }

            textPreviewGenerator.isTextPreviewable(mimeType) -> {
                val bitmap = textPreviewGenerator.generate(data, options)
                bitmap?.let {
                    ImageFetchResult(
                        image = bitmap.asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK
                    )
                } ?: fallbackIcon
            }

            else -> fallbackIcon
        }
    }

    class Factory @Inject constructor(
        @ApplicationContext private val context: Context,
        private val gatewaySwitch: GatewaySwitch,
        private val mimeTypeTool: MimeTypeTool,
        private val textPreviewGenerator: TextPreviewGenerator,
        private val apkPreviewGenerator: ApkPreviewGenerator,
    ) : Fetcher.Factory<APathLookup<*>> {

        override fun create(
            data: APathLookup<*>,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = PathPreviewFetcher(
            context,
            gatewaySwitch,
            mimeTypeTool,
            textPreviewGenerator,
            apkPreviewGenerator,
            data,
            options,
        )
    }

    companion object {
        private val TAG = logTag("Coil", "Fetcher", "Path")
    }
}

