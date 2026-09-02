package eu.darken.butler.common.coil

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.svg.SvgDecoder
import coil3.util.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coil.fetchers.AppIconFetcher
import eu.darken.butler.common.coil.fetchers.BitmapFetcher
import eu.darken.butler.common.coil.fetchers.PathPreviewFetcher
import eu.darken.butler.common.coil.fetchers.SharedContentPreviewFetcher
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.viewer.core.ViewerImageFetcher
import eu.darken.butler.viewer.core.ViewerImageKeyer
import eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewFetcher
import eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewKeyer
import javax.inject.Singleton


@InstallIn(SingletonComponent::class)
@Module
class CoilModule {

    @Singleton
    @Provides
    fun imageLoader(
        @ApplicationContext context: Context,
        generalSettings: GeneralSettings,
        appIconFetcherFactory: AppIconFetcher.Factory,
        pathPreviewFetcher: PathPreviewFetcher.Factory,
        sharedContentPreviewFetcher: SharedContentPreviewFetcher.Factory,
        bitmapFetcher: BitmapFetcher.Factory,
        workspacePreviewFetcher: WorkspacePreviewFetcher.Factory,
        viewerImageFetcher: ViewerImageFetcher.Factory,
        pathPreviewKeyer: PathPreviewKeyer,
        sharedContentPreviewKeyer: SharedContentPreviewKeyer,
        workspacePreviewKeyer: WorkspacePreviewKeyer,
        pkgIconKeyer: PkgIconKeyer,
        storageProviderIconKeyer: StorageProviderIconKeyer,
        viewerImageKeyer: ViewerImageKeyer,
        dispatcherProvider: DispatcherProvider,
    ): ImageLoader = ImageLoader.Builder(context).apply {
        if (BuildConfigWrap.DEBUG) {
            val logger = object : Logger {
                override var minLevel: Logger.Level = Logger.Level.Verbose
                override fun log(tag: String, level: Logger.Level, message: String?, throwable: Throwable?) {
                    log("Coil:$tag", Logging.Priority.fromAndroid(level.ordinal)) { "$message ${throwable?.asLog()}" }
                }
            }
            logger(logger)
        }

        diskCache {
            val cacheDir = context.cacheDir.resolve("preview_cache")
            log(TAG) { "Configuring disk cache: dir=$cacheDir" }

            DiskCache.Builder().apply {
                directory(cacheDir)
                maxSizeBytes(128 * 1024 * 1024L)
            }.build()
        }

        components {
            // Keyers - determine cache keys before lookup
            add(pathPreviewKeyer)
            add(sharedContentPreviewKeyer)
            add(workspacePreviewKeyer)
            add(pkgIconKeyer)
            add(storageProviderIconKeyer)
            add(viewerImageKeyer)

            // Fetchers - load images from various sources
            add(appIconFetcherFactory)
            add(pathPreviewFetcher)
            add(sharedContentPreviewFetcher)
            add(bitmapFetcher)
            add(workspacePreviewFetcher)
            add(viewerImageFetcher)

            // Decoders - decode special formats
            add(BoundedVideoFrameDecoder.Factory(baseDispatcher = dispatcherProvider.IO))
            add(SvgDecoder.Factory())
            // AnimatedImageDecoder needs API 28+; minSdk is 26, so the legacy GIF decoder stays.
            add(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    AnimatedImageDecoder.Factory()
                } else {
                    GifDecoder.Factory()
                }
            )
        }
        val cores = Runtime.getRuntime().availableProcessors()
        // Cache checks + keying: cheap, but must stay off the main thread (keyer reads DataStore).
        interceptorCoroutineContext(
            dispatcherProvider.Default.limitedParallelism((cores / 2).coerceAtLeast(2))
        )
        // Fetches are IO-bound (gateway file opens, APK/PDF/text preview generation) — bounded,
        // but on the IO dispatcher instead of occupying CPU lanes.
        fetcherCoroutineContext(
            dispatcherProvider.IO.limitedParallelism((cores / 2).coerceAtLeast(2))
        )
        // Bitmap decodes are the CPU work that competes with the UI thread during scroll — keep
        // them tighter than the fetch pipeline so fast flings don't starve the frame pipeline.
        decoderCoroutineContext(
            dispatcherProvider.Default.limitedParallelism((cores / 4).coerceAtLeast(2))
        )
    }.build()

    companion object {
        private val TAG = logTag("Coil", "Module")
    }
}
