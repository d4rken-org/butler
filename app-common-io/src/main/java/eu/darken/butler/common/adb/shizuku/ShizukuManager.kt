package eu.darken.butler.common.adb.shizuku

import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.isAdbConnectTimeout
import eu.darken.butler.common.adb.service.AdbServiceClient
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.ipc.IpcContract
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.toPkgId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    settings: AdbSettings,
    private val shizukuWrapper: ShizukuWrapper,
    val serviceClient: AdbServiceClient,
) {

    /** The connected ADB access server while the user opted in, null otherwise. */
    val shizukuBinder: Flow<AdbServer?> = settings.useShizuku.flow
        // Only the setting gates this: the connection flow itself is passive, and a manager installed
        // or started while Butler runs has to reach it without a restart.
        .flatMapLatest { if (it == true) shizukuWrapper.connection else flowOf(null) }
        .catch { e ->
            log(TAG, WARN) { "ADB server connection access failed: ${e.asLog()}" }
            emit(null)
        }
        .setupCommonEventHandlers(TAG) { "binder" }
        .onEach {
            log(TAG, VERBOSE) { "ADB server connection changed (${it != null}), invalidating caches" }
            invalidateShizukudCache()
        }
        .replayingShare(appScope)

    // The reference package plus every installed app that declares an ADB access manager permission.
    suspend fun managerIds(): Set<Pkg.Id> = setOf(PKG_ID) + shizukuWrapper.getManagerPackages().map { it.toPkgId() }

    /** The current connection's permission state, [AdbPermissionState.Unknown] while there is none. */
    val permissionState: Flow<AdbPermissionState> = shizukuWrapper.permissionState
        .setupCommonEventHandlers(TAG) { "permission" }
        .onEach {
            log(TAG, VERBOSE) { "Permission state changed ($it), invalidating caches" }
            invalidateShizukudCache()
        }
        .replayingShare(appScope)

    private val cacheLock = Mutex()
    private var isShizukudCache: Boolean? = null

    private suspend fun invalidateShizukudCache() = cacheLock.withLock { isShizukudCache = null }

    @Volatile private var lastShizukudResultInternal: Boolean? = null

    /**
     * What [isShizukud] last answered, or null if it never completed. Reads a recorded result
     * without probing, so a caller that must not start an ADB session (e.g. an error report) can
     * still say what the access state was.
     *
     * Separate from [isShizukudCache], which the early negative returns deliberately leave unwritten
     * and which would therefore read as "never probed" after a completed negative answer.
     */
    val lastShizukudResult: Boolean? get() = lastShizukudResultInternal

    /**
     * Is the device shizukud and we have access?
     */
    suspend fun isShizukud(): Boolean = cacheLock.withLock {
        isShizukudCache?.let {
            lastShizukudResultInternal = it
            return@withLock it
        }

        val availability = shizukuWrapper.availability()
        if (!availability.countsAsInstalled()) {
            log(TAG) { "isShizukud(): No ADB access manager is installed ($availability)" }
            lastShizukudResultInternal = false
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): ADB access manager is installed" }

        if (!availability.countsAsCompatible()) {
            log(TAG) { "isShizukud(): ADB access server is incompatible ($availability)" }
            lastShizukudResultInternal = false
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): ADB access server is compatible" }

        val granted = isGranted()
        if (granted == false) {
            log(TAG) { "isShizukud(): Permission not granted" }
            lastShizukudResultInternal = false
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): Permission is granted" }

        if (granted == null) {
            log(TAG) { "isShizukud(): Binder unavailable" }
            lastShizukudResultInternal = false
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): Binder available" }

        log(TAG, VERBOSE) { "isShizukud(): Checking availability of (Our) ShizukuService..." }
        isOurServiceAvailable().also {
            // checkBase() is blocking IPC: cancellation during the call isn't observed until the next
            // suspension, so a probe that raced with cancellation can produce a bogus negative —
            // never cache it.
            currentCoroutineContext().ensureActive()
            isShizukudCache = it
            lastShizukudResultInternal = it
            if (it) log(TAG, VERBOSE) { "isShizukud(): (Our) ShizukuService is available :)" }
            else log(TAG) { "isShizukud(): (Our) ShizukuService is unavailable" }
        }
    }

    // Reference package, also used as a fallback for previews and when nothing is installed.
    val shizukuPkgId: Pkg.Id
        get() = PKG_ID

    /**
     * The app providing ADB access, found by the permission it declares so forks and hidden-mode
     * installs are handled. Null if none is installed, or none declares it any more while its server
     * keeps running.
     */
    suspend fun getManagerId(): Pkg.Id? = shizukuWrapper.availability()?.packageName?.toPkgId()

    /** Null when it could not be read, which says nothing about what is installed. */
    suspend fun availability(): AdbAvailability? = shizukuWrapper.availability()

    /** Installed apps declaring a manager permission of [backend], preferred one first. */
    suspend fun getManagerIds(backend: AdbBackend): List<Pkg.Id> =
        shizukuWrapper.getManagerPackages(backend).map { it.toPkgId() }

    // Not cached: a stale "not installed" result would outlive a manager installed while Butler runs.
    suspend fun isInstalled(): Boolean = shizukuWrapper.availability().countsAsInstalled()
        .also { log(TAG) { "isInstalled(): $it" } }

    suspend fun isGranted(): Boolean? = shizukuWrapper.isGranted()

    // Not cached: a manager updated while Butler runs becomes compatible on its next connection.
    suspend fun isCompatible(): Boolean = shizukuWrapper.availability().countsAsCompatible()
        .also { log(TAG) { "isCompatible(): $it" } }

    /** An availability that could not be read is not proof of an installed manager. */
    private fun AdbAvailability?.countsAsInstalled(): Boolean = this != null && this !is AdbAvailability.NotInstalled

    /** An availability that could not be read is not proof of an incompatible server either. */
    private fun AdbAvailability?.countsAsCompatible(): Boolean = this !is AdbAvailability.Incompatible

    suspend fun requestPermission(): AdbPermissionState = shizukuWrapper.requestPermission()

    suspend fun isOurServiceAvailable(): Boolean = getServiceState() is ShizukuServiceState.Available

    /**
     * Same probe as [isOurServiceAvailable], but says WHY when the answer is no.
     *
     * The distinction that matters for the UI is "we have not finished looking" versus "we looked and
     * it will not work": only the latter is worth telling the user about, and only the latter should
     * offer a retry.
     */
    suspend fun getServiceState(): ShizukuServiceState = withContext(dispatcherProvider.IO) {
        when (isGranted()) {
            false -> {
                log(TAG, VERBOSE) { "getServiceState(): Shizuku permission not granted" }
                return@withContext ShizukuServiceState.PermissionDenied
            }
            // Not a denial: no live binder means the grant state cannot be read at all.
            null -> {
                log(TAG, VERBOSE) { "getServiceState(): No live binder, grant state unknown" }
                return@withContext ShizukuServiceState.Unknown
            }

            true -> {}
        }
        try {
            log(TAG, VERBOSE) { "getServiceState(): Requesting service client (CACHE MISS)" }
            val alive = serviceClient.get().use {
                // The round-trip is the liveness proof; the identity in its reply must still be the
                // one the connection was gated on (the client rejects anything else).
                IpcContract.decode(it.item.ipc.checkBase()) == it.item.hostIdentity
            }
            if (alive) {
                ShizukuServiceState.Available
            } else {
                // Connected, but the host handed back nothing usable.
                log(TAG, WARN) { "getServiceState(): checkBase() reply was missing or from another host" }
                ShizukuServiceState.Failed
            }
        } catch (e: CancellationException) {
            throw e // don't cache a cancelled probe as "unavailable"
        } catch (e: Exception) {
            log(TAG, WARN) { "getServiceState(): Error during checkBase(): ${e.asLog()}" }
            // A spent connect budget is the signature of Shizuku's user service never calling back,
            // but the same defect also surfaces as a handshake failure, so both are terminal.
            if (e.isAdbConnectTimeout()) ShizukuServiceState.TimedOut else ShizukuServiceState.Failed
        }
    }.also { log(TAG) { "getServiceState(): $it" } }

    /**
     * Did the user consent to Butler using Shizuku and is Shizuku available?
     */
    val useShizuku: Flow<Boolean> = settings.useShizuku.flow
        .flatMapLatest { isEnabled ->
            if (isEnabled != true) return@flatMapLatest flowOf(false)

            combine(
                shizukuBinder.map { }.onStart { emit(Unit) },
                permissionState.map { }.onStart { emit(Unit) },
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
        internal val PKG_ID = "moe.shizuku.privileged.api".toPkgId()
    }
}