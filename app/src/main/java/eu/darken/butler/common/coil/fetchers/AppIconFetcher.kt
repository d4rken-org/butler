package eu.darken.butler.common.coil.fetchers

import androidx.appcompat.content.res.AppCompatResources
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
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
        } ?: run {
            val drawable = AppCompatResources.getDrawable(options.context, R.drawable.ic_default_app_icon_24)!!
            drawable.setTintList(null) // Strip XML tint, let TintedAsyncImage apply theme-aware tint
            drawable
        }

        return ImageFetchResult(
            image = baseIcon.asImage(),
            isSampled = false,
            dataSource = DataSource.DISK
        )
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

