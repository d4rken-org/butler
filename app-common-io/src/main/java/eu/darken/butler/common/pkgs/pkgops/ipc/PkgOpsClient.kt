package eu.darken.butler.common.pkgs.pkgops.ipc

import android.content.pm.PackageInfo
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ipc.IpcClientModule
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.user.UserHandle2

class PkgOpsClient @AssistedInject constructor(
    @Assisted private val connection: PkgOpsConnection
) : IpcClientModule {

    fun getUserNameForUID(uid: Int): String? = try {
        connection.getUserNameForUID(uid)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "getUserNameForUID(uid=$uid) failed: ${it.asLog()}" }
        }
    }

    fun getGroupNameforGID(gid: Int): String? = try {
        connection.getGroupNameforGID(gid)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "getGroupNameforGID(gid=$gid) failed: ${it.asLog()}" }
        }
    }

    fun forceStop(packageName: String): Boolean = try {
        connection.forceStop(packageName)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "forceStop(packageName=$packageName) failed: ${it.asLog()}" }
        }
    }

    fun getRunningPackages(): Set<InstallId> = try {
        connection.getRunningPackages().pkgs
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "getRunningPackages() failed: ${it.asLog()}" }
        }
    }

    /**
     * Can fail if the amount of packages exceeds the IPC buffer size.
     * android.os.DeadObjectException: Transaction failed on small parcel; remote process probably died
     */
    fun getInstalledPackagesAsUser(flags: Long, userHandle: UserHandle2): List<PackageInfo> = try {
        connection.getInstalledPackagesAsUser(flags, userHandle.handleId)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "getInstalledPackagesAsUser(flags=$flags, userHandle=$userHandle) failed: ${it.asLog()}" }
        }
    }

    fun getInstalledPackagesAsUserStream(flags: Long, userHandle: UserHandle2): List<PackageInfo> = try {
        connection.getInstalledPackagesAsUserStream(flags, userHandle.handleId).toPackageInfos()
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) {
                "getInstalledPackagesAsUserStream(flags=$flags, userHandle=$userHandle) failed: ${it.asLog()}"
            }
        }
    }

    fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int): Unit = try {
        connection.setApplicationEnabledSetting(packageName, newState, flags)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) {
                "setApplicationEnabledSetting(packageName=$packageName, newState=$newState, flags=$flags) failed: ${it.asLog()}"
            }
        }
    }

    fun setComponentEnabledSetting(packageName: String, className: String, newState: Int, flags: Int): Unit = try {
        connection.setComponentEnabledSetting(packageName, className, newState, flags)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) {
                "setComponentEnabledSetting(packageName=$packageName, className=$className, newState=$newState, flags=$flags) failed: ${it.asLog()}"
            }
        }
    }

    fun grantPermission(id: InstallId, permission: Permission): Boolean = try {
        connection.grantPermission(id.pkgId.name, id.userHandle.handleId, permission.permissionId)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "grantPermission(id=$id, permission=$permission) failed: ${it.asLog()}" }
        }
    }

    fun revokePermission(id: InstallId, permission: Permission): Boolean = try {
        connection.revokePermission(id.pkgId.name, id.userHandle.handleId, permission.permissionId)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "revokePermission(id=$id, permission=$permission) failed: ${it.asLog()}" }
        }
    }

    fun setAppOps(id: InstallId, key: String, value: String): Boolean = try {
        connection.setAppOps(id.pkgId.name, id.userHandle.handleId, key, value)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "setAppOps(id=$id, key=$key, value=$value) failed: ${it.asLog()}" }
        }
    }

    fun uninstallPackage(id: InstallId): Boolean = try {
        connection.uninstallPackage(id.pkgId.name, id.userHandle.handleId)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "uninstallPackage(id=$id) failed: ${it.asLog()}" }
        }
    }

    fun clearData(id: InstallId): Boolean = try {
        connection.clearData(id.pkgId.name, id.userHandle.handleId)
    } catch (e: Exception) {
        throw e.refineException().also {
            log(TAG, ERROR) { "clearData(id=$id) failed: ${it.asLog()}" }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(connection: PkgOpsConnection): PkgOpsClient
    }

    companion object {
        val TAG = logTag("Pkg", "Ops", "Service", "Client")
    }
}