package eu.darken.butler.common.adb

import eu.darken.butler.common.adb.service.AdbServiceClient
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.Pkg
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdbManager @Inject constructor(
    private val shizukuManager: ShizukuManager,
    val serviceClient: AdbServiceClient,
) {

    suspend fun managerIds(): Set<Pkg.Id> = shizukuManager.managerIds()

    val useAdb = shizukuManager.useShizuku

    companion object {
        private val TAG = logTag("ADB", "Manager")
    }
}