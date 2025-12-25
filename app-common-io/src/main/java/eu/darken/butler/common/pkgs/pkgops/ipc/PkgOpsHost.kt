package eu.darken.butler.common.pkgs.pkgops.ipc

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ipc.IpcHostModule
import eu.darken.butler.common.ipc.RemoteInputStream
import eu.darken.butler.common.pkgs.deleteApplicationCacheFiles
import eu.darken.butler.common.pkgs.deleteApplicationCacheFilesAsUser
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.freeStorageAndNotify
import eu.darken.butler.common.pkgs.getInstalledPackagesAsUser
import eu.darken.butler.common.pkgs.pkgops.LibcoreTool
import eu.darken.butler.common.pkgs.pkgops.ProcessScanner
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.shell.SharedShell
import eu.darken.butler.common.user.UserHandle2
import eu.darken.flowshell.core.cmd.FlowCmd
import eu.darken.flowshell.core.cmd.execute
import eu.darken.flowshell.core.process.FlowProcess
import kotlinx.coroutines.runBlocking
import javax.inject.Inject


class PkgOpsHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libcoreTool: LibcoreTool,
    private val sharedShell: SharedShell,
    private val processScanner: ProcessScanner,
) : PkgOpsConnection.Stub(), IpcHostModule {

    private val pm: PackageManager
        get() = context.packageManager

    private val am: ActivityManager
        get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override fun getUserNameForUID(uid: Int): String? = try {
        libcoreTool.getNameForUid(uid)
    } catch (e: Exception) {
        log(TAG, ERROR) { "getUserNameForUID(uid=$uid) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun getGroupNameforGID(gid: Int): String? = try {
        libcoreTool.getNameForGid(gid)
    } catch (e: Exception) {
        log(TAG, ERROR) { "getGroupNameforGID(gid=$gid) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun getRunningPackages(): RunningPackagesResult = try {
        val result = try {
            am.runningAppProcesses!!
                .flatMap { it.pkgList.toList() }
                .distinct()
                .map {
                    // TODO can we get the correctly user handle?
                    InstallId(it.toPkgId(), UserHandle2())
                }
                .toSet()
        } catch (e: Exception) {
            log(TAG, ERROR) { "getRunningPackages(): runningAppProcesses failed due to $e " }
            runBlocking { processScanner.getRunningPackages() }
                .map { InstallId(it.pkgId, it.handle) }
                .toSet()
        }
        log(TAG, VERBOSE) { "getRunningPackages()=$result" }
        RunningPackagesResult(result)
    } catch (e: Exception) {
        log(TAG, ERROR) { "getRunningPackages() failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun forceStop(packageName: String): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val forceStopPackage = am.javaClass.getDeclaredMethod("forceStopPackage", String::class.java).apply {
            isAccessible = true
        }
        forceStopPackage.invoke(am, packageName)
        true
    } catch (e: Exception) {
        log(TAG, ERROR) { "forceStop(packageName=$packageName) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun clearCacheAsUser(packageName: String, handleId: Int): Boolean = try {
        log(TAG, VERBOSE) { "clearCache(packageName=$packageName, handleId=$handleId)..." }
        runBlocking { pm.deleteApplicationCacheFilesAsUser(packageName, handleId) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "clearCache(packageName=$packageName, handleId=$handleId) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun clearCache(packageName: String): Boolean = try {
        log(TAG, VERBOSE) { "clearCache(packageName=$packageName)..." }
        runBlocking { pm.deleteApplicationCacheFiles(packageName) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "clearCache(packageName=$packageName) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun trimCaches(desiredBytes: Long, storageId: String?): Boolean = try {
        log(TAG, VERBOSE) { "trimCaches(desiredBytes=$desiredBytes, storageId=$storageId)..." }
        runBlocking { pm.freeStorageAndNotify(desiredBytes, storageId) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "trimCaches(desiredBytes=$desiredBytes, storageId=$storageId) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun getInstalledPackagesAsUser(flags: Long, handleId: Int): List<PackageInfo> = try {
        log(TAG, VERBOSE) { "getInstalledPackagesAsUser($flags, $handleId)..." }

        pm.getInstalledPackagesAsUser(flags, UserHandle2(handleId)).also {
            log(TAG) { "getInstalledPackagesAsUser($flags, $handleId): ${it.size}" }
        }
    } catch (e: Exception) {
        log(TAG, ERROR) { "getInstalledPackagesAsUser(flags=$flags, handleId=$handleId) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun getInstalledPackagesAsUserStream(flags: Long, handleId: Int): RemoteInputStream = try {
        log(TAG, VERBOSE) { "getInstalledPackagesAsUserStream($flags, $handleId)..." }
        pm.getInstalledPackagesAsUser(flags, UserHandle2(handleId)).also {
            log(TAG) { "getInstalledPackagesAsUserStream($flags, $handleId): ${it.size}" }
        }.toRemoteInputStream()
    } catch (e: Exception) {
        log(TAG, ERROR) { "getInstalledPackagesAsUserStream(flags=$flags, handleId=$handleId) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int) = try {
        log(TAG, VERBOSE) { "setApplicationEnabledSetting($packageName, $newState, $flags)..." }
        pm.setApplicationEnabledSetting(packageName, newState, flags)
        log(TAG, VERBOSE) { "setApplicationEnabledSetting($packageName, $newState, $flags) succesful" }
    } catch (e: Exception) {
        log(TAG, ERROR) { "setApplicationEnabledSetting($packageName, $newState, $flags) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun grantPermission(packageName: String, handleId: Int, permissionId: String): Boolean = try {
        log(TAG, VERBOSE) { "grantPermission($packageName, $handleId, $permissionId)..." }
        val result = runBlocking {
            sharedShell.useRes {
                FlowCmd("pm grant --user $handleId $packageName $permissionId").execute(it)
            }
        }
        result.exitCode == FlowProcess.ExitCode.OK
    } catch (e: Exception) {
        log(TAG, ERROR) { "grantPermission($packageName, $handleId, $permissionId) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun revokePermission(packageName: String, handleId: Int, permissionId: String): Boolean = try {
        log(TAG, VERBOSE) { "revokePermission($packageName, $handleId, $permissionId)..." }
        val result = runBlocking {
            sharedShell.useRes {
                FlowCmd("pm revoke --user $handleId $packageName $permissionId").execute(it)
            }
        }
        result.exitCode == FlowProcess.ExitCode.OK
    } catch (e: Exception) {
        log(TAG, ERROR) { "revokePermission($packageName, $handleId, $permissionId) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun setAppOps(packageName: String, handleId: Int, key: String, value: String): Boolean = try {
        log(TAG, VERBOSE) { "setAppOps($packageName, $handleId, $key, $value)..." }
        val result = runBlocking {
            sharedShell.useRes {
                FlowCmd("appops set --user $handleId $packageName $key $value ").execute(it)
            }
        }
        result.exitCode == FlowProcess.ExitCode.OK
    } catch (e: Exception) {
        log(TAG, ERROR) { "setAppOps($packageName, $handleId, $key, $value) failed: ${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    companion object {
        val TAG = logTag("Pkg", "Ops", "Service", "Host", Bugs.processTag)
    }
}