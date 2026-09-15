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

    val shizukuBinder: Flow<ShizukuBaseServiceBinder?> = settings.useShizuku.flow
        // Only touch the Shizuku binder if the user opted in AND Shizuku is actually installed.
        // Otherwise (e.g. useShizuku left enabled after uninstalling Shizuku) every subscription would
        // probe the absent service and spam "binder haven't been received" on each resume.
        .flatMapLatest { if (it == true && isInstalled()) shizukuWrapper.baseServiceBinder else flowOf(null) }
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

    // The two reference packages plus every installed app that declares a manager permission.
    suspend fun managerIds(): Set<Pkg.Id> =
        setOf(PKG_ID, PORTER_PKG_ID) + shizukuWrapper.getManagerPackages().map { it.toPkgId() }

    /**
     * Only the managers that are actually installed, unlike [managerIds] which always carries the
     * reference packages and therefore cannot answer whether anything is installed at all.
     */
    suspend fun installedManagerIds(): Set<Pkg.Id> = shizukuWrapper.getManagerPackages().map { it.toPkgId() }.toSet()

    /** Installed managers of the active backend's family, see [ShizukuWrapper.getActiveManagerPackages]. */
    suspend fun activeManagerIds(): Set<Pkg.Id> = shizukuWrapper.getActiveManagerPackages().map { it.toPkgId() }.toSet()

    /**
     * An installed manager belonging to the OTHER family, i.e. one this process cannot talk to.
     *
     * The backend latches at provider init, so a manager installed afterwards stays invisible to
     * [getManagerId] until the app is fully restarted. This is what lets the UI name it.
     */
    suspend fun inactiveFamilyManagerId(): Pkg.Id? {
        val active = shizukuWrapper.getActiveManagerPackages().toSet()
        return shizukuWrapper.getManagerPackages().firstOrNull { it !in active }?.toPkgId()
    }

    suspend fun activeBackend(): AdbBackend = shizukuWrapper.activeBackend()

    val permissionGrantEvents: Flow<ShizukuWrapper.ShizukuPermissionRequest> = shizukuWrapper.permissionGrantEvents
        .setupCommonEventHandlers(TAG) { "grantEvents" }
        .replayingShare(appScope)

    private val cacheLock = Mutex()
    private var isShizukudCache: Boolean? = null

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

        if (!isInstalled()) {
            log(TAG) { "isShizukud(): Shizuku is not installed" }
            lastShizukudResultInternal = false
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): Shizuku is installed" }

        if (!isCompatible()) {
            log(TAG) { "isShizukud(): Shizuku version is too old" }
            lastShizukudResultInternal = false
            return@withLock false
        }
        log(TAG, VERBOSE) { "isShizukud(): Shizuku is recent enough" }

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

    // Placeholder for previews and for when nothing is installed: the reference package of the
    // backend this process would connect through, so it can't name a family we cannot talk to.
    suspend fun referenceManagerId(): Pkg.Id = when (activeBackend()) {
        AdbBackend.PORTER -> PORTER_PKG_ID
        AdbBackend.SHIZUKU -> PKG_ID
    }

    /**
     * The manager package of the backend this process connects through, resolved via that backend's
     * permission so forks and hidden-mode installs still resolve, or null when that backend's manager
     * is not installed.
     */
    suspend fun getManagerId(): Pkg.Id? = shizukuWrapper.getManagerPackage()?.toPkgId()

    // Not cached: a stale "not installed" result would keep the binder gate (see shizukuBinder) closed
    // even after a manager for the active backend gets installed, and the lookup is cheap. It cannot
    // help across backends though: the active backend is latched for the process lifetime, so a
    // manager installed for the other one stays invisible until restart however often we re-probe.
    suspend fun isInstalled(): Boolean {
        val installed = getManagerId() != null
        log(TAG) { "isInstalled(): $installed" }
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
        internal val PKG_ID = "moe.shizuku.privileged.api".toPkgId()
        internal val PORTER_PKG_ID = "eu.darken.porter".toPkgId()
    }
}