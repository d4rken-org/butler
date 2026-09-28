package eu.darken.butler.setup.core.shizuku

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.AdbAvailability
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.adb.shizuku.AdbPermissionState
import eu.darken.butler.common.adb.shizuku.AdbServer
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.getLabel2
import eu.darken.butler.common.pkgs.getLaunchIntent
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.rngString
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.setup.core.SetupModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class ShizukuSetupModule @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val adbSettings: AdbSettings,
    private val shizukuManager: ShizukuManager,
    rootManager: RootManager,
) : SetupModule {

    override val type = SetupModule.Type.SHIZUKU

    private val refreshTrigger = MutableStateFlow(rngString)

    // Last known concrete Result, kept so re-subscription (e.g. returning to the dashboard) can emit it
    // immediately instead of regressing to Loading and flickering the setup card while the availability
    // probe re-runs (a cold AdbHost bind can take ~10s). Only ever holds a real Result, never Loading.
    @Volatile
    private var lastResult: Result? = null

    private val permissionRequestLock = Any()
    private var permissionRequest: Deferred<AdbPermissionState?>? = null

    /** Overridden in tests to keep the timeout case fast, never in production. */
    internal var permissionRequestTimeoutMs: Long = PERMISSION_REQUEST_TIMEOUT_MS

    // Shared, so a switch-on and a Grant tap join one prompt instead of stacking two. Bounded, so an
    // unanswered or dismissed prompt does not block the next attempt; a late Allow still reaches the
    // card through the manager's permission state.
    private fun sharedPermissionRequest(): Deferred<AdbPermissionState?> = synchronized(permissionRequestLock) {
        permissionRequest?.takeIf { it.isActive } ?: appScope
            .async {
                log(TAG) { "Requesting ADB permission" }
                val timeout = permissionRequestTimeoutMs
                val result = withTimeoutOrNull(timeout) { shizukuManager.requestPermission() }
                if (result == null) log(TAG, WARN) { "No answer within ${timeout}ms, next attempt asks anew" }
                log(TAG) { "ADB permission request result: $result" }
                result
            }
            .also { request ->
                permissionRequest = request
                request.invokeOnCompletion {
                    synchronized(permissionRequestLock) {
                        if (permissionRequest === request) permissionRequest = null
                    }
                }
            }
    }

    override val state: Flow<SetupModule.State> = combine(
        refreshTrigger,
        adbSettings.useShizuku.flow,
        rootManager.useRoot,
    ) { _, useShizuku, useRoot ->
        if (useShizuku != true) {
            return@combine flow {
                emit(
                    probe(
                        useShizuku = useShizuku,
                        useRoot = useRoot,
                        server = null,
                        permission = AdbPermissionState.Unknown,
                        probeService = false,
                    )
                )
            }
        }

        combine(
            shizukuManager.permissionState.onStart { emit(AdbPermissionState.Unknown) },
            shizukuManager.shizukuBinder.onStart { emit(null) },
        ) { permission, server ->
            probe(
                useShizuku = useShizuku,
                useRoot = useRoot,
                server = server,
                permission = permission,
                probeService = true,
            )
        }
    }
        .flatMapLatest { it }
        .onEach { lastResult = it }
        .onStart<SetupModule.State> {
            // Don't regress to Loading if we already know the result: emit the last known state so the
            // dashboard setup card doesn't flicker while the probe re-runs in the background. Guard
            // against a useShizuku change that happened while we had no subscribers.
            val cached = lastResult
            if (cached != null && cached.useShizuku == adbSettings.useShizuku.value()) {
                emit(cached)
            } else {
                emit(Loading())
            }
        }
        .onEach { log(TAG) { "New Shizuku setup state: $it" } }
        .replayingShare(appScope)

    private suspend fun probe(
        useShizuku: Boolean?,
        useRoot: Boolean,
        server: AdbServer?,
        permission: AdbPermissionState,
        probeService: Boolean,
    ): Result {
        val availability = shizukuManager.availability()
        val incompatible = availability as? AdbAvailability.Incompatible
        val openTarget = availability?.let { findOpenTarget(it) }
        return Result(
            useShizuku = useShizuku,
            backend = availability?.backend ?: server?.backend,
            pkg = openTarget,
            managerLabel = openTarget?.let { labelOf(it) },
            // A live connection proves a manager even when the availability could not be read.
            isInstalled = server != null || (availability != null && availability !is AdbAvailability.NotInstalled),
            isUnrecognized = availability is AdbAvailability.InstalledUnrecognized,
            isCompatible = incompatible == null,
            serverTooOld = incompatible?.serverTooOld == true,
            clientTooOld = incompatible?.clientTooOld == true,
            permissionState = permission,
            basicService = server != null,
            serviceState = if (probeService) shizukuManager.getServiceState() else ShizukuServiceState.NotChecked,
            alsoHasRoot = useRoot,
        )
    }

    /**
     * The permission owner can be a manager without a launcher activity: Shizuku+'s Compat Hub owns the
     * stock Shizuku permission. Its siblings of the same backend are tried before settling for it, and
     * opening it then falls back to its app info page. Opening the other backend's manager cannot
     * affect the connection this card is about.
     */
    private suspend fun findOpenTarget(availability: AdbAvailability): Pkg.Id? {
        val owner = availability.packageName?.toPkgId() ?: return null
        val backend = availability.backend ?: return owner
        val candidates = (listOf(owner) + shizukuManager.getManagerIds(backend)).distinct()
        return withContext(dispatcherProvider.IO) {
            candidates.firstOrNull { it.isLaunchable() }
        } ?: owner
    }

    private fun Pkg.Id.isLaunchable(): Boolean = try {
        getLaunchIntent(context) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "isLaunchable($this) failed: ${e.asLog()}" }
        false
    }

    // Must not throw: an exception here (a PackageManager binder death, a vendor SecurityException)
    // would kill the sharing coroutine and take every later subscriber with it.
    private suspend fun labelOf(pkgId: Pkg.Id): String? = withContext(dispatcherProvider.IO) {
        try {
            context.packageManager.getLabel2(pkgId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "labelOf($pkgId) failed: ${e.asLog()}" }
            null
        }
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = rngString
    }

    /**
     * Switching on asks for permission once when a server is reachable and Butler is not granted. The
     * switch stays on whatever the answer is: a denial is shown on the card instead of flipping the
     * switch back. Later asks happen only through [grantAccess].
     */
    suspend fun toggleUseShizuku(useShizuku: Boolean?) {
        log(TAG) { "toggleUseShizuku(useShizuku=$useShizuku)" }
        // Drop any cached state so we don't replay a stale Result for the previous setting.
        lastResult = null

        if (useShizuku != true) {
            adbSettings.useShizuku.value(useShizuku)
            return
        }

        val couldUseShizuku = shizukuManager.useShizuku.first()
        try {
            if (shizukuManager.isGranted() == false) {
                val grantResult = sharedPermissionRequest().await()
                log(TAG) { "Permission grant result was $grantResult" }
            } else {
                log(TAG) { "Not asking for permission: already granted or no reachable ADB server" }
            }
        } finally {
            withContext(NonCancellable) { adbSettings.useShizuku.value(true) }
        }

        if (!couldUseShizuku) {
            // Wait for the server connection to show up instead of guessing with a fixed delay.
            withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) {
                shizukuManager.shizukuBinder.filter { it != null }.first()
            } ?: log(TAG, WARN) { "ADB server did not connect within ${SERVICE_BIND_TIMEOUT_MS}ms" }
        }
    }

    suspend fun grantAccess() {
        log(TAG) { "grantAccess()" }
        val result = sharedPermissionRequest().await()
        log(TAG) { "grantAccess(): Permission request result was $result" }
        // Neither an Allow nor a Deny is guaranteed to change a watched flow, so re-probe to show it.
        refresh()
    }

    data class Loading(
        override val startAt: Instant = Clock.System.now(),
    ) : SetupModule.State.Loading {
        override val type: SetupModule.Type = SetupModule.Type.SHIZUKU
    }

    data class Result(
        val useShizuku: Boolean?,
        /** The server family in play, null while nothing is installed. */
        val backend: AdbBackend? = null,
        /** The manager app to open, null when there is none to open. */
        val pkg: Pkg.Id? = null,
        /** Display name of [pkg], null when it could not be read. */
        val managerLabel: String? = null,
        override val isInstalled: Boolean = false,
        /** The manager declares a backend's permission but is not one the SDK knows, e.g. a renamed fork. */
        val isUnrecognized: Boolean = false,
        val isCompatible: Boolean = true,
        /** The user has to update the manager. */
        val serverTooOld: Boolean = false,
        /** Butler has to ship a newer client. */
        val clientTooOld: Boolean = false,
        val permissionState: AdbPermissionState = AdbPermissionState.Unknown,
        val basicService: Boolean = false,
        val serviceState: ShizukuServiceState = ShizukuServiceState.NotChecked,
        val alsoHasRoot: Boolean = false,
    ) : SetupModule.State.Current {

        val ourService: Boolean
            get() = serviceState is ShizukuServiceState.Available

        val isPermissionDenied: Boolean
            get() = permissionState is AdbPermissionState.Denied || serviceState is ShizukuServiceState.PermissionDenied

        override val type: SetupModule.Type = SetupModule.Type.SHIZUKU

        override val isAvailable: Boolean
            get() = isInstalled

        override val isComplete: Boolean = when {
            useShizuku == true -> ourService // Only complete if enabled AND connected
            useShizuku == false -> true // Complete if explicitly disabled
            else -> false // Not complete if not configured
        }
    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: ShizukuSetupModule): SetupModule
    }

    companion object {
        private val TAG = logTag("Setup", "ADB", "Shizuku", "Module")
        private const val SERVICE_BIND_TIMEOUT_MS = 5_000L
        internal const val PERMISSION_REQUEST_TIMEOUT_MS = 30_000L
    }
}
