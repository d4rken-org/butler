package eu.darken.butler.common.coil

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
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
        pathPreviewKeyer: PathPreviewKeyer,
        sharedContentPreviewKeyer: SharedContentPreviewKeyer,
        workspacePreviewKeyer: WorkspacePreviewKeyer,
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

            // Fetchers - load images from various sources
            add(appIconFetcherFactory)
            add(pathPreviewFetcher)
            add(sharedContentPreviewFetcher)
            add(bitmapFetcher)
            add(workspacePreviewFetcher)

            // Decoders - decode special formats
            add(BoundedVideoFrameDecoder.Factory(baseDispatcher = dispatcherProvider.IO))
        }
        coroutineContext(
            dispatcherProvider.Default.limitedParallelism(
                (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
            )
        )
    }.build()

    companion object {
        private val TAG = logTag("Coil", "Module")
    }
}
