package eu.darken.butler.common.pkgs

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caDrawable
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.io.R
import eu.darken.butler.common.pkgs.features.AppStore

@Keep
sealed class AKnownPkg(override val id: Pkg.Id) : Pkg {
    constructor(rawPkgId: String) : this(Pkg.Id(rawPkgId))

    @get:StringRes open val labelRes: Int? = null
    @get:DrawableRes open val iconRes: Int? = R.drawable.ic_default_app_icon_24

    override val label: CaString?
        get() = caString { context ->
            context.packageManager.getLabel2(id)?.let { return@caString it }

            labelRes?.let { return@caString context.getString(it) }

            id.name
        }

    override val icon: CaDrawable?
        get() = caDrawable { context ->
            context.packageManager.getIcon2(id)?.let { return@caDrawable it }

            iconRes
                ?.let { ContextCompat.getDrawable(context, it) }
                ?.let { return@caDrawable it }

            ContextCompat.getDrawable(context, R.drawable.ic_default_app_icon_24)!!
        }

    data object AndroidSystem : AKnownPkg("android")

    data object GooglePlay : AKnownPkg("com.android.vending"), AppStore {
        override val iconRes: Int = R.drawable.ic_gplay_24
        override val urlGenerator: ((Pkg.Id) -> String) = {
            "https://play.google.com/store/apps/details?id=${it.name}"
        }
    }

    data object VivoAppStore : AKnownPkg("com.vivo.appstore"), AppStore

    data object OppoMarket : AKnownPkg("com.oppo.market"), AppStore

    data object HuaweiAppGallery : AKnownPkg("com.huawei.appmarket"), AppStore

    data object SamsungAppStore : AKnownPkg("com.sec.android.app.samsungapps"), AppStore

    data object XiaomiAppStore : AKnownPkg("com.xiaomi.mipicks"), AppStore

    companion object {
        val values: List<AKnownPkg> = listOf(
            AndroidSystem,
            GooglePlay,
            VivoAppStore,
            OppoMarket,
            HuaweiAppGallery,
            SamsungAppStore,
            XiaomiAppStore
        )

        val APP_STORES by lazy { values.filterIsInstance<AppStore>() }
        val OEM_STORES by lazy { APP_STORES - GooglePlay }
    }
}

fun Pkg.Id.toKnownPkg(): Pkg? = AKnownPkg.values.singleOrNull { it.id == this@toKnownPkg }