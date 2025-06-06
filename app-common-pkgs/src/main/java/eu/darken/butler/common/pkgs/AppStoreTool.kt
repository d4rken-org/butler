package eu.darken.butler.common.pkgs

import android.content.Context
import android.content.Intent
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.pkgs.features.AppStore
import javax.inject.Inject


@Reusable
class AppStoreTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webpageTool: WebpageTool,
) {

    fun openAppStoreFor(target: Pkg, installer: Pkg) {
        if (hasApiLevel(24)) {
            val generic = Intent(Intent.ACTION_SHOW_APP_INFO).apply {
                setPackage(installer.packageName)
            }
            val specific = generic.resolveToActivity()?.apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, target.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (specific != null) {
                log { "Using resovled app store intent: $specific" }
                context.startActivity(specific)
                return
            }
        }
        if (installer is AppStore) {
            installer.urlGenerator?.invoke(target.id)?.let {
                log { "Using urlgenerator from known app store: $it" }
                webpageTool.open(it)
                return
            }
        }
        log(WARN) { "No known way to open $target in $installer" }
    }

    private fun Intent.resolveToActivity(): Intent? = context.packageManager.resolveActivity(this, 0)?.let { result ->
        Intent(this.action)
            .apply { setClassName(result.activityInfo.packageName, result.activityInfo.name) }
    }
}