package eu.darken.butler.common.pkgs

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Inject

class SDMaidToolGplay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pkgRepo: PkgRepo,
    private val webpageTool: WebpageTool,
) : SDMaidTool {

    override val installUrl: String = INSTALL_URL

    override suspend fun isInstalled(): Boolean = pkgRepo.isInstalled(AKnownPkg.SDMaidSE.id)

    override fun launch(): Boolean {
        val intent = getLaunchIntent() ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to launch SD Maid: ${e.asLog()}" }
            false
        }
    }

    override fun getLaunchIntent(): Intent? = context.packageManager
        .getLaunchIntentForPackage(AKnownPkg.SDMaidSE.packageName)
        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    override fun openInstallPage() {
        webpageTool.open(installUrl)
    }

    companion object {
        private const val INSTALL_URL = "https://play.google.com/store/apps/details?id=eu.darken.sdmse"
        private val TAG = logTag("SDMaid","Tool", "Gplay")
    }
}
