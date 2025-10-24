package eu.darken.butler.common.coil

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.util.Logger
import coil3.video.VideoFrameDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.main.core.GeneralSettings
import okio.Path.Companion.toPath
import javax.inject.Provider
import javax.inject.Singleton


@InstallIn(SingletonComponent::class)
@Module
class CoilModule {

    @Provides
    fun imageLoader(
        @ApplicationContext context: Context,
        generalSettings: GeneralSettings,
        appIconFetcherFactory: AppIconFetcher.Factory,
        pathPreviewFetcher: PathPreviewFetcher.Factory,
        bitmapFetcher: BitmapFetcher.Factory,
        workspacePreviewFetcher: eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewFetcher.Factory,
        workspacePreviewKeyer: eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewKeyer,
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
            val cacheDir = context.cacheDir.resolve("coil_image_cache")
            log(TAG) { "Configuring disk cache: dir=$cacheDir, maxSize=250MB" }
            DiskCache.Builder()
                .directory(cacheDir.absolutePath.toPath())
                .maxSizeBytes(250L * 1024 * 1024) // 250 MB
                .build()
        }
        components {
            // Keyers - determine cache keys before lookup
            add(workspacePreviewKeyer)

            // Fetchers - load images from various sources
            add(appIconFetcherFactory)
            add(pathPreviewFetcher)
            add(bitmapFetcher)
            add(workspacePreviewFetcher)

            // Decoders - decode special formats
            add(VideoFrameDecoder.Factory())
        }
        coroutineContext(
            dispatcherProvider.Default.limitedParallelism(
                (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
            )
        )
    }.build()

    @Singleton
    @Provides
    fun imageLoaderFactory(
        imageLoaderSource: Provider<ImageLoader>
    ): SingletonImageLoader.Factory = SingletonImageLoader.Factory {
        log(TAG) { "Preparing imageloader factory" }
        imageLoaderSource.get()
    }

    companion object {
        private val TAG = logTag("Coil", "Module")
    }
}
