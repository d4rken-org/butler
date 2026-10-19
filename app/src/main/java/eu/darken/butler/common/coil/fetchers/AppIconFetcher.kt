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
import eu.darken.butler.common.coil.iconRasterSize
import eu.darken.butler.common.coil.sanitizeIconSize
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.funnel.IPCFunnel
import eu.darken.butler.common.io.R
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.getIcon2
import javax.inject.Inject

class AppIconFetcher @Inject constructor(
    private val ipcFunnel: IPCFunnel,
    private val data: Pkg,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        log(TAG) { "Fetching $data" }
        // For an installed package only the package manager's icon counts as artwork; whatever else
        // its icon property yields is a glyph, tinted at draw time so it follows the current theme.
        val (artwork, glyph) = ipcFunnel.use {
            if (data is Installed) {
                val artwork = packageManager.getIcon2(data.id)
                artwork to if (artwork == null) data.icon?.get(options.context) else null
            } else {
                (data.icon?.get(options.context) ?: packageManager.getIcon2(data.id)) to null
            }
        }

        if (artwork != null) {
            return ImageFetchResult(
                image = artwork.toShareableImage(),
                isSampled = false,
                dataSource = DataSource.DISK,
            )
        }

        val drawable = glyph?.constantState?.newDrawable()?.mutate()
            ?: AppCompatResources.getDrawable(options.context, R.drawable.ic_default_app_icon_24)!!
        drawable.setTintList(null) // Strip XML tint, let TintedAsyncImage apply theme-aware tint
        return ImageFetchResult(
            image = drawable.asImage(),
            isSampled = false,
            // Marks the glyph for tinting; counterpart: TintedAsyncImage.shouldTint
            dataSource = DataSource.MEMORY,
        )
    }

    /**
     * Coil refuses to memory cache non-bitmap images, and most modern launcher icons are
     * `AdaptiveIconDrawable`. Rasterizing here is what makes the memory cache apply to them.
     */
    private fun Drawable.toShareableImage(): Image {
        if (this is BitmapDrawable) return asImage()

        val rasterSize = options.iconRasterSize()
        val width = rasterSize?.width ?: intrinsicWidth.sanitizeIconSize()
        val height = rasterSize?.height ?: intrinsicHeight.sanitizeIconSize()

        return toBitmap(width = width, height = height).asImage()
    }

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
    }
}

