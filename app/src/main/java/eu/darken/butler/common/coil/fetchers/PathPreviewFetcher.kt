package eu.darken.butler.common.coil.fetchers

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.appcompat.content.res.AppCompatResources
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
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.R
import eu.darken.butler.common.MimeTypeTool
import eu.darken.butler.common.coil.PathPreviewKeyer
import eu.darken.butler.common.coil.targetEdgePx
import eu.darken.butler.common.coil.toImageSource
import eu.darken.butler.common.coil.use
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.extension
import eu.darken.butler.common.files.iconRes
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.preview.PdfPreviewGenerator
import eu.darken.butler.common.hashing.Hasher
import eu.darken.butler.common.pkgs.ApkIconExtractor
import eu.darken.butler.common.hashing.hash
import okio.Buffer
import okio.FileSystem
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@OptIn(ExperimentalCoilApi::class)
class PathPreviewFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val mimeTypeTool: MimeTypeTool,
    private val textPreviewGenerator: TextPreviewGenerator,
    private val apkPreviewGenerator: ApkPreviewGenerator,
    private val apkIconExtractor: ApkIconExtractor,
    private val pdfPreviewGenerator: PdfPreviewGenerator,
    private val keyer: PathPreviewKeyer,
    private val diskCache: Lazy<DiskCache?>,
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
        val drawable = AppCompatResources.getDrawable(options.context, R.drawable.ic_heart_24)!!
        drawable.setTintList(null) // Strip XML tint, let TintedAsyncImage apply theme-aware tint
        ImageFetchResult(
            image = drawable.asImage(),
            isSampled = false,
            dataSource = DataSource.MEMORY
        )
    }

    private val fallbackIcon: FetchResult by lazy {
        val drawable = AppCompatResources.getDrawable(options.context, data.fileType.iconRes)!!
        drawable.setTintList(null) // Strip XML tint, let TintedAsyncImage apply theme-aware tint
        ImageFetchResult(
            image = drawable.asImage(),
            isSampled = false,
            dataSource = DataSource.MEMORY
        )
    }


    override suspend fun fetch(): FetchResult {
        if (isEasterEggPath()) return easterEggIcon

        if (data.fileType != FileType.FILE || data.size == 0L) return fallbackIcon

        // Archive entries would need decompression to scratch storage for a thumbnail;
        // previews must never trigger that implicitly.
        if (data.lookedUp is ArchivePath) return fallbackIcon

        // Network files show a generic type icon: a thumbnail would download every visible file
        // over the network just to scroll a folder.
        if (data.lookedUp is SmbPath) return fallbackIcon

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

            data.lookedUp.extension.equals("apk", ignoreCase = true) ||
                mimeType == "application/vnd.android.package-archive" -> {
                // Preferred: no-copy icon via a seekable PFD (API 30+), works for SAF and local.
                val extracted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    gatewaySwitch.openReadPFD(data.lookedUp)?.use { pfd ->
                        apkIconExtractor.extract(pfd, options.targetEdgePx())
                    }
                } else {
                    null
                }
                when {
                    extracted != null -> ImageFetchResult(
                        image = extracted.asImage(),
                        isSampled = true,
                        dataSource = DataSource.DISK,
                    )
                    // Legacy fallback: getPackageArchiveInfo needs a real path -> LocalPath only.
                    data.lookedUp is LocalPath -> apkPreviewGenerator.generate(data.lookedUp as LocalPath)
                        ?.let {
                            ImageFetchResult(
                                image = it.asImage(),
                                isSampled = false,
                                dataSource = DataSource.DISK,
                            )
                        } ?: fallbackIcon

                    else -> fallbackIcon
                }
            }

            mimeType == "application/pdf" || data.lookedUp.extension.equals("pdf", ignoreCase = true) -> {
                val bitmap = gatewaySwitch.openReadPFD(data.lookedUp)?.let { pfd ->
                    pdfPreviewGenerator.renderPage(pfd, options.targetEdgePx()) // owns + closes pfd
                }
                bitmap?.let {
                    ImageFetchResult(
                        image = it.asImage(),
                        isSampled = true,
                        dataSource = DataSource.DISK,
                    )
                } ?: fallbackIcon
            }

            textPreviewGenerator.isTextPreviewable(mimeType, data.lookedUp.extension) -> {
                // Generate cache key
                val cacheKey = keyer.key(data, options)

                // Check disk cache first
                diskCache.value?.openSnapshot(cacheKey)?.use { snapshot ->
                    log(TAG) { "Text preview disk cache HIT: ${data.path}" }
                    val cachedBytes = diskCache.value!!.fileSystem.read(snapshot.data) {
                        readByteArray()
                    }
                    val bufferedSource = Buffer().write(cachedBytes)
                    return SourceFetchResult(
                        source = ImageSource(
                            source = bufferedSource,
                            fileSystem = FileSystem.SYSTEM,
                        ),
                        mimeType = "image/png",
                        dataSource = DataSource.DISK
                    )
                }

                // Cache miss - generate preview
                log(TAG) { "Text preview disk cache MISS: ${data.path}" }
                val bitmap = textPreviewGenerator.generate(data, options) ?: return fallbackIcon

                // Write to disk cache if enabled
                if (options.diskCachePolicy.writeEnabled) {
                    val pngBytes = ByteArrayOutputStream().use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        stream.toByteArray()
                    }
                    diskCache.value?.openEditor(cacheKey)?.use { editor ->
                        diskCache.value!!.fileSystem.write(editor.data) {
                            write(pngBytes)
                        }
                        log(TAG) { "Wrote text preview to disk cache: ${data.path}" }
                    }
                }

                ImageFetchResult(
                    image = bitmap.asImage(),
                    isSampled = false,
                    dataSource = DataSource.NETWORK
                )
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
        private val apkIconExtractor: ApkIconExtractor,
        private val pdfPreviewGenerator: PdfPreviewGenerator,
        private val keyer: PathPreviewKeyer,
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
            apkIconExtractor,
            pdfPreviewGenerator,
            keyer,
            lazy { imageLoader.diskCache },
            data,
            options,
        )
    }

    companion object {
        private val TAG = logTag("Coil", "Fetcher", "Path")
    }
}

