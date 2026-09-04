package eu.darken.butler.common.pkgs

import android.content.ComponentName
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.*
import android.content.pm.SharedLibraryInfo
import android.graphics.drawable.Drawable
import android.os.RemoteException
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.user.UserHandle2
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.jvmErasure

fun PackageManager.getPackageInfo2(
    pkgId: Pkg.Id,
    flags: Int = 0
): PackageInfo? = try {
    getPackageInfo(pkgId.name, flags)
} catch (_: NameNotFoundException) {
    null
}

/**
 * A blank label counts as no label: callers fall back to the package name on null, and an app whose
 * label resource resolves to "" would otherwise render as an empty title with nothing beside it.
 */
fun PackageManager.getLabel2(
    pkgId: Pkg.Id,
): String? = getPackageInfo2(pkgId)
    ?.applicationInfo
    ?.let {
        if (it.labelRes != 0) it.loadLabel(this).toString()
        else it.nonLocalizedLabel?.toString()
    }
    ?.takeIf { it.isNotBlank() }

fun PackageManager.getIcon2(
    pkgId: Pkg.Id,
): Drawable? = getPackageInfo2(pkgId)
    ?.applicationInfo
    ?.let { if (it.icon != 0) it.loadIcon(this) else null }


fun PackageManager.getInstalledPackagesAsUser(
    flags: Long,
    userHandle: UserHandle2,
) = try {
    val functions = PackageManager::class.memberFunctions.filter { it.name == "getInstalledPackagesAsUser" }
    if (hasApiLevel(33)) {
        @Suppress("NewApi", "UNCHECKED_CAST")
        functions
            .first {
                val arg1 = it.parameters[1].type.jvmErasure
                val arg2 = it.parameters[2].type.jvmErasure
                PackageInfoFlags::class.isSubclassOf(arg1) && Int::class.isSubclassOf(arg2)
            }
            .call(this, PackageInfoFlags.of(flags), userHandle.handleId) as List<PackageInfo>
    } else {
        @Suppress("UNCHECKED_CAST")
        functions
            .first {
                val arg1 = it.parameters[1].type.jvmErasure
                val arg2 = it.parameters[2].type.jvmErasure
                Int::class.isSubclassOf(arg1) && Int::class.isSubclassOf(arg2)
            }
            .call(this, flags.toInt(), userHandle.handleId) as List<PackageInfo>
    }
} catch (e: Exception) {
    log(ERROR) { e.asLog() }
    throw e
}

// WORKAROUND
fun PackageManager.getSharedLibraries2(flags: Int): List<SharedLibraryInfo> = try {
    getSharedLibraries(flags)
} catch (e: Exception) {
    log("PackageManager", ERROR) { "Failed getSharedLibraries($flags)" }
    // https://github.com/d4rken/sdmaid-public/issues/3100
    if (hasApiLevel(29)) throw e else emptyList()
}

fun PackageManager.toggleSelfComponent(
    component: ComponentName,
    enabled: Boolean,
) {
    log { "toggleSelfComponent($component,$enabled)" }
    setComponentEnabledSetting(
        component,
        when {
            enabled -> COMPONENT_ENABLED_STATE_ENABLED
            else -> COMPONENT_ENABLED_STATE_DISABLED
        },
        DONT_KILL_APP
    )
}
