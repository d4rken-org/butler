package eu.darken.butler.common.coil.fetchers

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.funnel.IPCFunnel
import eu.darken.butler.common.io.R
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.getIcon2
import javax.inject.Inject

class AppIconFetcher @Inject constructor(
    private val ipcFunnel: IPCFunnel,
    private val data: Pkg,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        log(TAG) { "Fetching $data" }
        val baseIcon = ipcFunnel.use {
            data.icon?.get(options.context) ?: packageManager.getIcon2(data.id)
        }

        val image = if (baseIcon != null) {
            baseIcon.toShareableImage()
        } else {
            val drawable = AppCompatResources.getDrawable(options.context, R.drawable.ic_default_app_icon_24)!!
            drawable.setTintList(null) // Strip XML tint, let TintedAsyncImage apply theme-aware tint
            drawable.asImage()
        }

        return ImageFetchResult(
            image = image,
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }

    /**
     * Coil refuses to memory cache non-bitmap images, and most modern launcher icons are
     * `AdaptiveIconDrawable`. Rasterizing here is what makes the memory cache apply to them.
     */
    private fun Drawable.toShareableImage(): Image {
        if (this is BitmapDrawable) return asImage()

        val requestedWidth = options.size.width.pxOrElse { -1 }
        val requestedHeight = options.size.height.pxOrElse { -1 }
        val useRequested = requestedWidth > 0 && requestedHeight > 0

        val width = (if (useRequested) requestedWidth else intrinsicWidth).sanitizeIconSize()
        val height = (if (useRequested) requestedHeight else intrinsicHeight).sanitizeIconSize()

        return toBitmap(width = width, height = height).asImage()
    }

    private fun Int.sanitizeIconSize(): Int = if (this <= 0) MAX_ICON_PX else coerceAtMost(MAX_ICON_PX)

    class Factory @Inject constructor(
        private val ipcFunnel: IPCFunnel,
    ) : Fetcher.Factory<Pkg> {

        override fun create(
            data: Pkg,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = AppIconFetcher(ipcFunnel, data, options)
    }

    companion object {
        private val TAG = logTag("Coil", "Fetcher", "Pkg")

        /** Upper bound so a 56dp grid cell never allocates a huge bitmap. */
        private const val MAX_ICON_PX = 192
    }
}

