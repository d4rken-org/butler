package eu.darken.butler.common.pkgs.pkgops

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager.*
import android.content.pm.SharedLibraryInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.adb.service.runModuleAction
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.local.LocalFileMaterializer
import eu.darken.butler.common.funnel.IPCFunnel
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.permissions.Permission.*
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.container.PkgArchive
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.InstallerInfo
import eu.darken.butler.common.pkgs.features.getInstallerInfo
import eu.darken.butler.common.pkgs.getLabel2
import eu.darken.butler.common.pkgs.getSharedLibraries2
import eu.darken.butler.common.pkgs.pkgops.ipc.PkgOpsClient
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootUnavailableException
import eu.darken.butler.common.root.canUseRootNow
import eu.darken.butler.common.root.service.runModuleAction
import eu.darken.butler.common.sharedresource.HasSharedResource
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.sharedresource.keepResourcesAlive
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserManager2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

@Singleton
class PkgOps @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    dispatcherProvider: DispatcherProvider,
    @ApplicationContext private val context: Context,
    private val ipcFunnel: IPCFunnel,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val usageStatsManager: UsageStatsManager,
    private val storageStatsManager: StorageStatsManager,
    private val userManager2: UserManager2,
    private val localFileMaterializer: LocalFileMaterializer,
) : HasSharedResource<Any> {

    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    private suspend fun <T> adbOps(action: suspend (PkgOpsClient) -> T): T {
        if (!adbManager.canUseAdbNow()) throw AdbUnavailableException()
        return keepResourcesAlive(adbManager.serviceClient) {
            adbManager.serviceClient.runModuleAction(PkgOpsClient::class.java) { action(it) }
        }
    }

    private suspend fun <T> rootOps(action: suspend (PkgOpsClient) -> T): T {
        if (!rootManager.canUseRootNow()) throw RootUnavailableException()
        return keepResourcesAlive(rootManager.serviceClient) {
            rootManager.serviceClient.runModuleAction(PkgOpsClient::class.java) { action(it) }
        }
    }

    suspend fun getUserNameForUID(uid: Int): String? = rootOps { client ->
        client.getUserNameForUID(uid)
    }

    suspend fun getGroupNameforGID(gid: Int): String? = rootOps { client ->
        client.getGroupNameforGID(gid)
    }

    fun getUIDForUserName(userName: String): Int? = when (val gid = Process.getUidForName(userName)) {
        -1 -> null
        else -> gid
    }

    fun getGIDForGroupName(groupName: String): Int? = when (val gid = Process.getGidForName(groupName)) {
        -1 -> null
        else -> gid
    }

    suspend fun forceStop(pkgId: Pkg.Id, mode: Mode = Mode.AUTO): Boolean {
        log(TAG, VERBOSE) { "forceStop($pkgId, mode=$mode)" }
        try {
            val opsAction = { opsClient: PkgOpsClient ->
                opsClient.forceStop(pkgId.name)
            }

            if (adbManager.canUseAdbNow() && (mode == Mode.AUTO || mode == Mode.ADB)) {
                log(TAG) { "forceStop($pkgId, $mode->ADB)" }
                return adbOps { opsAction(it) }

            }

            if (rootManager.canUseRootNow() && (mode == Mode.AUTO || mode == Mode.ROOT)) {
                log(TAG) { "forceStop($pkgId, $mode->ROOT)" }
                return rootOps { opsAction(it) }
            }

            throw ElevatedAccessUnavailableException("Mode $mode is unavailable")
        } catch (e: Exception) {
            if (e is ElevatedAccessUnavailableException) {
                log(TAG, DEBUG) { "forceStop(...): $mode unavailable for $pkgId" }
            } else {
                log(TAG, WARN) { "forceStop($pkgId, mode=$mode) failed: $e" }
            }
            throw PkgOpsException(message = "changePackageState($pkgId, $mode) failed", cause = e)
        }
    }

    suspend fun queryPkg(pkgName: Pkg.Id, flags: Long, userHandle: UserHandle2): PackageInfo? = ipcFunnel.use {
        try {
            ipcFunnel.use {
                if (hasApiLevel(33)) {
                    @Suppress("NewApi")
                    packageManager.getPackageInfo(pkgName.name, PackageInfoFlags.of(flags))
                } else {
                    packageManager.getPackageInfo(pkgName.name, flags.toInt())
                }
            }
        } catch (e: NameNotFoundException) {
            log(TAG, VERBOSE) { "queryPkg($pkgName, $flags): null" }
            null
        }
    }

    suspend fun queryPkgs(flags: Int) = queryPkgs(flags.toLong())

    @Suppress("QueryPermissionsNeeded")
    suspend fun queryPkgs(flags: Long): Collection<PackageInfo> = ipcFunnel.use {
        if (hasApiLevel(33)) {
            @Suppress("NewApi")
            packageManager.getInstalledPackages(PackageInfoFlags.of(flags))
        } else {
            packageManager.getInstalledPackages(flags.toInt())
        }
    }

    suspend fun queryPkgs(flags: Int, userHandle: UserHandle2) = queryPkgs(flags.toLong(), userHandle)

    suspend fun queryPkgs(flags: Long, userHandle: UserHandle2): Collection<PackageInfo> = when {
        rootManager.canUseRootNow() -> {
            rootOps { it.getInstalledPackagesAsUserStream(flags, userHandle) }
        }

        adbManager.canUseAdbNow() -> {
            adbOps { it.getInstalledPackagesAsUserStream(flags, userHandle) }
        }

        else -> {
            throw IllegalStateException("Can't get user specific packages (neither root nor adb) access available")
        }
    }

    suspend fun getInstallerData(
        pkgInfos: Collection<PackageInfo>
    ): Map<PackageInfo, InstallerInfo> = ipcFunnel.use {
        pkgInfos.associateWith { it.getInstallerInfo(packageManager) }
    }

    suspend fun isInstalleMaybe(pkg: Pkg.Id, userHandle: UserHandle2): Boolean = try {
        ipcFunnel.use {
            packageManager.getPackageUid(pkg.name, 0)
        }
        true
    } catch (e: NameNotFoundException) {
        false
    }

    suspend fun queryAppInfos(
        pkg: Pkg.Id,
        flags: Int = MATCH_UNINSTALLED_PACKAGES
    ): ApplicationInfo? = ipcFunnel.use {
        try {
            packageManager.getApplicationInfo(pkg.name, flags)
        } catch (e: NameNotFoundException) {
            log(TAG, WARN) { "queryAppInfos($pkg=pkg,flags=$flags) packageName not found." }
            null
        }
    }

    suspend fun getLabel(pkgId: Pkg.Id): String? = ipcFunnel.use {
        try {
            ipcFunnel.use {
                packageManager.getLabel2(pkgId)
            }
        } catch (e: NameNotFoundException) {
            log(TAG, WARN) { "getLabel(packageName=$pkgId) packageName not found." }
            null
        }
    }

    suspend fun getLabel(applicationInfo: ApplicationInfo): String? = ipcFunnel.use {
        try {
            applicationInfo.loadLabel(packageManager).toString()
        } catch (e: NameNotFoundException) {
            log(TAG, WARN) { "getLabel(applicationInfo=$applicationInfo) packageName not found." }
            null
        }
    }

    /**
     * Reads metadata from an APK archive at [path].
     *
     * Works for any [APath] backend: non-local paths (SAF today, FTP/SFTP/HTTP in future) are
     * materialized to a temp file first, because [PackageManager.getPackageArchiveInfo] only accepts
     * a real filesystem path. The result is metadata-only (package name, version, permissions): the
     * underlying [PackageInfo] references temp paths (`sourceDir`, `publicSourceDir`, `splitSourceDirs`,
     * `nativeLibraryDir`) that no longer exist once this returns, so do NOT load resources from its raw
     * `applicationInfo` (e.g. `loadIcon()`/`loadLabel()`).
     *
     * Returns null if the archive cannot be read or parsed.
     */
    suspend fun viewArchive(path: APath<*>, flags: Int = 0): PkgArchive? = try {
        localFileMaterializer.useLocalFile(path) { jFile ->
            if (!jFile.exists()) return@useLocalFile null

            ipcFunnel.use {
                packageManager.getPackageArchiveInfo(jFile.path, flags)?.let {
                    PkgArchive(
                        id = it.packageName.toPkgId(),
                        packageInfo = it,
                    )
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "viewArchive($path) failed: ${e.asLog()}" }
        null
    }

    suspend fun getIcon(pkg: Pkg.Id): Drawable? {
        val appInfo = queryAppInfos(pkg, MATCH_UNINSTALLED_PACKAGES)
        return appInfo?.let { getIcon(it) }
    }

    suspend fun getIcon(appInfo: ApplicationInfo): Drawable? = ipcFunnel.use {
        try {
            appInfo.loadIcon(packageManager)
        } catch (e: Exception) {
            log(TAG) { "Failed to get icon ${e.asLog()}" }
            null
        }
    }

    suspend fun getSharedLibraries(
        flags: Int = 0
    ): List<SharedLibraryInfo> = ipcFunnel.use {
        packageManager.getSharedLibraries2(flags)
    }

    suspend fun changePackageState(id: Pkg.Id, enabled: Boolean, mode: Mode = Mode.AUTO) {
        log(TAG, VERBOSE) { "changePackageState($id, enabled=$enabled, mode=$mode)" }
        try {
            if (mode == Mode.NORMAL) throw PkgOpsException("changePackageState($id,$enabled) does not support mode=NORMAL")

            val newState = when (enabled) {
                true -> COMPONENT_ENABLED_STATE_ENABLED
                false -> COMPONENT_ENABLED_STATE_DISABLED_USER
            }

            val opsAction = { opsClient: PkgOpsClient ->
                opsClient.setApplicationEnabledSetting(
                    packageName = id.name,
                    newState = newState,
                    flags = run {
                        @Suppress("NewApi")
                        if (hasApiLevel(30)) SYNCHRONOUS else 0
                    }
                )
            }

            if (adbManager.canUseAdbNow() && (mode == Mode.AUTO || mode == Mode.ADB)) {
                log(TAG) { "changePackageState($id, enabled=$enabled, $mode->ADB)" }
                adbOps { opsAction(it) }
                return
            }

            if (rootManager.canUseRootNow() && (mode == Mode.AUTO || mode == Mode.ROOT)) {
                log(TAG) { "changePackageState($id, enabled=$enabled, $mode->ROOT)" }
                rootOps { opsAction(it) }
                return
            }

            throw ElevatedAccessUnavailableException("Mode $mode is unavailable")
        } catch (e: Exception) {
            if (e is ElevatedAccessUnavailableException) {
                log(TAG, DEBUG) { "changePackageState(...): $mode unavailable for $id" }
            } else {
                log(TAG, WARN) { "changePackageState($id, enabled=$enabled, mode=$mode) failed: $e" }
            }
            throw PkgOpsException(message = "changePackageState($id, $enabled, $mode) failed", cause = e)
        }
    }

    /**
     * Enables/disables a single manifest component.
     *
     * Same transport and permission as [changePackageState] ([android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE]),
     * only the target differs. `DONT_KILL_APP` is deliberately not passed, matching `pm disable`:
     * the owning app is killed so the new state takes effect immediately.
     */
    suspend fun changeComponentState(id: Pkg.Id, className: String, enabled: Boolean, mode: Mode = Mode.AUTO) {
        log(TAG, VERBOSE) { "changeComponentState($id, $className, enabled=$enabled, mode=$mode)" }
        try {
            if (mode == Mode.NORMAL) {
                throw PkgOpsException("changeComponentState($id,$className,$enabled) does not support mode=NORMAL")
            }

            // Component-level disable: DISABLED_USER is the application-level form.
            val newState = when (enabled) {
                true -> COMPONENT_ENABLED_STATE_ENABLED
                false -> COMPONENT_ENABLED_STATE_DISABLED
            }

            val opsAction = { opsClient: PkgOpsClient ->
                opsClient.setComponentEnabledSetting(
                    packageName = id.name,
                    className = className,
                    newState = newState,
                    flags = run {
                        @Suppress("NewApi")
                        if (hasApiLevel(30)) SYNCHRONOUS else 0
                    }
                )
            }

            if (adbManager.canUseAdbNow() && (mode == Mode.AUTO || mode == Mode.ADB)) {
                log(TAG) { "changeComponentState($id, $className, enabled=$enabled, $mode->ADB)" }
                adbOps { opsAction(it) }
                return
            }

            if (rootManager.canUseRootNow() && (mode == Mode.AUTO || mode == Mode.ROOT)) {
                log(TAG) { "changeComponentState($id, $className, enabled=$enabled, $mode->ROOT)" }
                rootOps { opsAction(it) }
                return
            }

            throw ElevatedAccessUnavailableException("Mode $mode is unavailable")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ElevatedAccessUnavailableException) {
                log(TAG, DEBUG) { "changeComponentState(...): $mode unavailable for $id" }
            } else {
                log(TAG, WARN) { "changeComponentState($id, $className, enabled=$enabled, mode=$mode) failed: $e" }
            }
            throw PkgOpsException(message = "changeComponentState($id, $className, $enabled, $mode) failed", cause = e)
        }
    }

    suspend fun uninstall(id: InstallId, mode: Mode = Mode.AUTO): Boolean {
        log(TAG, VERBOSE) { "uninstall($id, mode=$mode)" }
        try {
            if (mode == Mode.NORMAL) throw PkgOpsException("uninstall($id) does not support mode=NORMAL")

            val opsAction = { opsClient: PkgOpsClient ->
                opsClient.uninstallPackage(id)
            }

            if (adbManager.canUseAdbNow() && (mode == Mode.AUTO || mode == Mode.ADB)) {
                log(TAG) { "uninstall($id, $mode->ADB)" }
                return adbOps { opsAction(it) }
            }

            if (rootManager.canUseRootNow() && (mode == Mode.AUTO || mode == Mode.ROOT)) {
                log(TAG) { "uninstall($id, $mode->ROOT)" }
                return rootOps { opsAction(it) }
            }

            throw ElevatedAccessUnavailableException("Mode $mode is unavailable")
        } catch (e: Exception) {
            if (e is ElevatedAccessUnavailableException) {
                log(TAG, DEBUG) { "uninstall(...): $mode unavailable for $id" }
            } else {
                log(TAG, WARN) { "uninstall($id, mode=$mode) failed: $e" }
            }
            throw PkgOpsException(message = "uninstall($id, $mode) failed", cause = e)
        }
    }

    suspend fun clearData(id: InstallId, mode: Mode = Mode.AUTO): Boolean {
        log(TAG, VERBOSE) { "clearData($id, mode=$mode)" }
        try {
            if (mode == Mode.NORMAL) throw PkgOpsException("clearData($id) does not support mode=NORMAL")

            val opsAction = { opsClient: PkgOpsClient ->
                opsClient.clearData(id)
            }

            if (adbManager.canUseAdbNow() && (mode == Mode.AUTO || mode == Mode.ADB)) {
                log(TAG) { "clearData($id, $mode->ADB)" }
                return adbOps { opsAction(it) }
            }

            if (rootManager.canUseRootNow() && (mode == Mode.AUTO || mode == Mode.ROOT)) {
                log(TAG) { "clearData($id, $mode->ROOT)" }
                return rootOps { opsAction(it) }
            }

            throw ElevatedAccessUnavailableException("Mode $mode is unavailable")
        } catch (e: Exception) {
            if (e is ElevatedAccessUnavailableException) {
                log(TAG, DEBUG) { "clearData(...): $mode unavailable for $id" }
            } else {
                log(TAG, WARN) { "clearData($id, mode=$mode) failed: $e" }
            }
            throw PkgOpsException(message = "clearData($id, $mode) failed", cause = e)
        }
    }

    suspend fun getRunningPackages(mode: Mode = Mode.AUTO): Set<InstallId> {
        try {
            if (adbManager.canUseAdbNow() && (mode == Mode.AUTO || mode == Mode.ADB)) {
                log(TAG, VERBOSE) { "getRunningPackages($mode->ADB)" }
                return adbOps { it.getRunningPackages() }
            }

            if (rootManager.canUseRootNow() && (mode == Mode.AUTO || mode == Mode.ROOT)) {
                log(TAG, VERBOSE) { "getRunningPackages($mode->ROOT)" }
                return rootOps { it.getRunningPackages() }
            }

            if (PACKAGE_USAGE_STATS.isGranted(context) && (mode == Mode.AUTO || mode == Mode.NORMAL)) {
                log(TAG, VERBOSE) { "getRunningPackages($mode->NORMAL)" }
                val now = System.currentTimeMillis()
                val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 240 * 1000, now)
                val currentUser = userManager2.currentUser()
                return stats
                    .groupBy { it.packageName }
                    .map { (_, value) -> value.maxBy { it.lastTimeUsed } }
                    .filter {
                        val secondsSinceLastUse = (System.currentTimeMillis() - it.lastTimeUsed) / 1000L
                        secondsSinceLastUse < 60
                    }
                    .map { InstallId(it.packageName.toPkgId(), currentUser.handle) }
                    .toSet()
            }

            throw ElevatedAccessUnavailableException("Mode $mode is unavailable")
        } catch (e: Exception) {
            if (e is ElevatedAccessUnavailableException) {
                log(TAG, DEBUG) { "getRunningPackages(...): $mode unavailable" }
            } else {
                log(TAG, WARN) { "getRunningPackages($mode) failed: ${e.asLog()}" }
            }
            throw PkgOpsException(message = "getRunningPackages($mode) failed", cause = e)
        }
    }

    suspend fun grantPermission(id: InstallId, permission: Permission, mode: Mode = Mode.AUTO): Boolean {
        try {
            log(TAG) { "grantPermission($id, $permission, $mode)" }
            if (mode == Mode.NORMAL) throw PkgOpsException("grantPermission($id, $permission) does not support mode=NORMAL")

            if (adbManager.canUseAdbNow() && (mode == Mode.AUTO || mode == Mode.ADB)) {
                log(TAG) { "grantPermission($id, $permission, $mode->ADB)" }
                return adbOps { it.grantPermission(id, permission) }
            }

            if (rootManager.canUseRootNow() && (mode == Mode.AUTO || mode == Mode.ROOT)) {
                log(TAG) { "grantPermission($id, $permission, $mode->ROOT)" }
                return rootOps { it.grantPermission(id, permission) }

            }

            throw ElevatedAccessUnavailableException("Mode $mode is unavailable")
        } catch (e: Exception) {
            if (e is ElevatedAccessUnavailableException) {
                log(TAG, DEBUG) { "grantPermission(...): $mode unavailable for $id" }
            } else {
                log(TAG, WARN) { "grantPermission($id, $permission, $mode) failed: ${e.asLog()}" }
            }
            throw PkgOpsException(message = "grantPermission($id, $permission, $mode) failed", cause = e)
        }
    }

    suspend fun revokePermission(id: InstallId, permission: Permission, mode: Mode = Mode.AUTO): Boolean {
        try {
            log(TAG) { "revokePermission($id, $permission, $mode)" }
            if (mode == Mode.NORMAL) throw PkgOpsException("revokePermission($id, $permission) does not support mode=NORMAL")

            if (adbManager.canUseAdbNow() && (mode == Mode.AUTO || mode == Mode.ADB)) {
                log(TAG) { "revokePermission($id, $permission, $mode->ADB)" }
                return adbOps { it.revokePermission(id, permission) }
            }

            if (rootManager.canUseRootNow() && (mode == Mode.AUTO || mode == Mode.ROOT)) {
                log(TAG) { "revokePermission($id, $permission, $mode->ROOT)" }
                return rootOps { it.revokePermission(id, permission) }

            }

            throw ElevatedAccessUnavailableException("Mode $mode is unavailable")
        } catch (e: Exception) {
            if (e is ElevatedAccessUnavailableException) {
                log(TAG, DEBUG) { "grantPermission(...): $mode unavailable for $id" }
            } else {
                log(TAG, WARN) { "grantPermission($id, $permission, $mode) failed: ${e.asLog()}" }
            }
            throw PkgOpsException(message = "grantPermission($id, $permission, $mode) failed", cause = e)
        }
    }

    suspend fun setAppOps(
        id: InstallId,
        key: AppOpsKey,
        value: AppOpsValue,
        mode: Mode = Mode.AUTO
    ): Boolean {
        try {
            log(TAG) { "setAppOps($id, $key, $value, $mode)" }
            if (mode == Mode.NORMAL) throw PkgOpsException("setAppOps($id, $key, $value) does not support mode=NORMAL")

            if (adbManager.canUseAdbNow() && (mode == Mode.AUTO || mode == Mode.ADB)) {
                log(TAG) { "setAppOps($id, $key, $value, $mode->ADB)" }
                return adbOps { it.setAppOps(id, key.raw, value.raw) }
            }

            if (rootManager.canUseRootNow() && (mode == Mode.AUTO || mode == Mode.ROOT)) {
                log(TAG) { "setAppOps($id, $key, $value, $mode->ROOT)" }
                return rootOps { it.setAppOps(id, key.raw, value.raw) }

            }

            throw ElevatedAccessUnavailableException("Mode $mode is unavailable")
        } catch (e: Exception) {
            if (e is ElevatedAccessUnavailableException) {
                log(TAG, DEBUG) { "setAppOps(...): $mode unavailable for $id" }
            } else {
                log(TAG, WARN) { "setAppOps($id, $key, $value, $mode) failed: ${e.asLog()}" }
            }
            throw PkgOpsException(message = "setAppOps($id, $key, $value $mode) failed", cause = e)
        }
    }

    data class SizeStats(
        val appBytes: Long,
        val cacheBytes: Long,
        val externalCacheBytes: Long?,
        val dataBytes: Long,
    ) {
        // StorageStats.getDataBytes() already includes getCacheBytes(), adding cache would double-count it.
        val total: Long
            get() = appBytes + dataBytes
    }

    suspend fun querySizeStats(
        installId: InstallId,
        storageUUID: Uuid = StorageManager.UUID_DEFAULT.toKotlinUuid()
    ): SizeStats? = try {
        log(TAG, VERBOSE) { "querySizeStats($installId,$storageUUID)" }
        val stats = storageStatsManager.queryStatsForPackage(
            storageUUID.toJavaUuid(),
            installId.pkgId.name,
            installId.userHandle.asUserHandle(),
        )
        SizeStats(
            appBytes = stats.appBytes,
            cacheBytes = stats.cacheBytes,
            externalCacheBytes = if (hasApiLevel(31)) {
                @Suppress("NewApi")
                stats.externalCacheBytes
            } else null,
            dataBytes = stats.dataBytes,
        ).also { log(TAG, VERBOSE) { "querySizeStats($installId,$storageUUID) -> $it" } }
    } catch (e: NameNotFoundException) {
        null
    } catch (e: Exception) {
        log(TAG, ERROR) { "Failed to querySizeStats for $installId: ${e.asLog()}" }
        null
    }

    enum class AppOpsKey(val raw: String) {
        GET_USAGE_STATS("GET_USAGE_STATS"),
        MANAGE_EXTERNAL_STORAGE("MANAGE_EXTERNAL_STORAGE"),
        ;
    }

    enum class AppOpsValue(val raw: String) {
        ALLOW("allow"),
        ;
    }

    enum class Mode {
        AUTO, NORMAL, ROOT, ADB
    }

    companion object {
        val TAG = logTag("Pkg", "Ops")
    }
}