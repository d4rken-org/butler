package eu.darken.butler.common.coil

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import eu.darken.butler.common.MimeTypeTool
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.asFile
import eu.darken.butler.common.files.extensions.extension
import eu.darken.butler.common.files.iconRes
import javax.inject.Inject

class PathPreviewFetcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val mimeTypeTool: MimeTypeTool,
    private val data: APathLookup<*>,
    private val options: Options,
) : Fetcher {

    private val fallbackIcon: FetchResult by lazy {
        ImageFetchResult(
            image = ContextCompat.getDrawable(options.context, data.fileType.iconRes)!!.asImage(),
            isSampled = false,
            dataSource = DataSource.MEMORY
        )
    }

    private val pacMan: PackageManager
        get() = context.packageManager

    override suspend fun fetch(): FetchResult {
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
                val file = data.lookedUp.asFile()

                val iconDrawable = file
                    .takeIf { it.canRead() }
                    ?.let { pacMan.getPackageArchiveInfo(it.path, PackageManager.GET_META_DATA) }
                    ?.let {
                        (it.applicationInfo ?: ApplicationInfo()).apply {
                            sourceDir = file.path
                            publicSourceDir = file.path
                        }
                    }
                    ?.let { pacMan.getApplicationIcon(it) }

                iconDrawable?.let {
                    ImageFetchResult(
                        image = it.asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK
                    )
                } ?: fallbackIcon
            }

            else -> fallbackIcon
        }
    }

    class Factory @Inject constructor(
        @param:ApplicationContext private val context: Context,
        private val gatewaySwitch: GatewaySwitch,
        private val mimeTypeTool: MimeTypeTool,
    ) : Fetcher.Factory<APathLookup<*>> {

        override fun create(
            data: APathLookup<*>,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = PathPreviewFetcher(
            context,
            gatewaySwitch,
            mimeTypeTool,
            data,
            options,
        )
    }
}

