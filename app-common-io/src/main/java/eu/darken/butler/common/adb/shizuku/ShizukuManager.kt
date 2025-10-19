package eu.darken.butler.common.adb.shizuku

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.service.AdbServiceClient
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.root.RootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    settings: AdbSettings,
    private val shizukuWrapper: ShizukuWrapper,
    val serviceClient: AdbServiceClient,
) {

    val shizukuBinder: Flow<ShizukuBaseServiceBinder?> = settings.useShizuku.flow
        .flatMapLatest { if (it == true) shizukuWrapper.baseServiceBinder else flowOf(null) }
        .catch { e ->
            log(TAG, WARN) { "Shizuku binder access failed: ${e.asLog()}" }
            emit(null)
        }
        .setupCommonEventHandlers(TAG) { "binder" }
        .onEach {
            log(TAG, VERBOSE) { "Shizuku binder changed (${it != null}), invalidating caches" }
            cacheLock.withLock {
                isShizukudCache = null
            }
        }
        .replayingShare(appScope)

    suspend fun managerIds() = KNOWN_ADB_MANAGERS

    val permissionGrantEvents: Flow<ShizukuWrapper.ShizukuPermissionRequest> = shizukuWrapper.permissionGrantEvents
        .setupCommonEventHandlers(TAG) { "grantEvents" }
        .replayingShare(appScope)

    private val cacheLock = Mutex()
    private var isShizukudCache: Boolean? = null

    /**
     * Is the device shizukud and we have access?
     */
    suspend fun isShizukud(): Boolean = cacheLock.withLock {
        isShizukudCache?.let { return@withLock it }

        if (!isInstalled()) {
            log(TAG) { "isShizukud(): Shizuku is not installed" }
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): Shizuku is installed" }

        if (!isCompatible()) {
            log(TAG) { "isShizukud(): Shizuku version is too old" }
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): Shizuku is recent enough" }

        val granted = isGranted()
        if (granted == false) {
            log(TAG) { "isShizukud(): Permission not granted" }
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): Permission is granted" }

        if (granted == null) {
            log(TAG) { "isShizukud(): Binder unavailable" }
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): Binder available" }

        log(TAG, VERBOSE) { "isShizukud(): Checking availability of (Our) ShizukuService..." }
        isOurServiceAvailable().also {
            isShizukudCache = it
            if (it) log(TAG, VERBOSE) { "isShizukud(): (Our) ShizukuService is available :)" }
            else log(TAG) { "isShizukud(): (Our) ShizukuService is unavailable" }
        }
    }

    val shizukuPkgId: Pkg.Id
        get() = KNOWN_ADB_MANAGERS.first()


    suspend fun isInstalled(): Boolean {
        val installed = KNOWN_ADB_MANAGERS.any {
            try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(it.name, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        log(RootManager.Companion.TAG) { "isInstalled(): $installed" }
        return installed
    }

    suspend fun isGranted(): Boolean? = shizukuWrapper.isGranted()

    private var isCompatibleCache: Boolean? = null
    private val isCompatibleLock = Mutex()

    suspend fun isCompatible(): Boolean = isCompatibleLock.withLock {
        isCompatibleCache?.let { return@withLock it }

        shizukuWrapper.isCompatible().also {
            log(TAG) { "isCompatible(): $it" }
            isCompatibleCache = it
        }
    }

    suspend fun requestPermission() = shizukuWrapper.requestPermission()

    suspend fun isOurServiceAvailable(): Boolean = withContext(dispatcherProvider.IO) {
        try {
            log(TAG, VERBOSE) { "isOurServiceAvailable(): Requesting service client (CACHE MISS)" }
            serviceClient.get().use { it.item.ipc.checkBase() != null }
        } catch (e: Exception) {
            log(TAG, WARN) { "isOurServiceAvailable(): Error during checkBase(): $e" }
            false
        }
    }.also { log(TAG) { "isOurServiceAvailable(): $it" } }

    /**
     * Did the user consent to Butler using Shizuku and is Shizuku available?
     */
    val useShizuku: Flow<Boolean> = settings.useShizuku.flow
        .flatMapLatest { isEnabled ->
            if (isEnabled != true) return@flatMapLatest flowOf(false)

            combine(
                shizukuBinder.map { }.onStart { emit(Unit) },
                permissionGrantEvents.map { }.onStart { emit(Unit) },
            ) { _, _ -> isShizukud() }
        }
        .stateIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 60 * 1000,
                replayExpirationMillis = 0,
            ),
            initialValue = null
        )
        .filterNotNull()

    companion object {
        private val TAG = logTag("ADB", "Shizuku", "Manager")
        private val KNOWN_ADB_MANAGERS = setOf(
            "moe.shizuku.privileged.api"
        ).map { it.toPkgId() }.toSet()
    }
}