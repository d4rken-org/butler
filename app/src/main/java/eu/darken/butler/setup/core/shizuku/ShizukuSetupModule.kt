package eu.darken.butler.setup.core.shizuku

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.AdbPermissionState
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.rngString
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.setup.core.SetupModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class ShizukuSetupModule @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
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

    private val permissionRequester = shizukuManager.shizukuBinder
        .onEach {
            if (adbSettings.useShizuku.value() == true && shizukuManager.isGranted() == false) {
                log(TAG) { "Requesting Shizuku permission for us..." }
                shizukuManager.requestPermission()
            }
        }
        .map { }
        .onStart { emit(Unit) }

    override val state: Flow<SetupModule.State> = combine(
        refreshTrigger,
        adbSettings.useShizuku.flow,
        rootManager.useRoot,
    ) { _, useShizuku, useRoot ->
        val managerId = shizukuManager.getManagerId()
        val baseState = Result(
            pkg = managerId ?: shizukuManager.shizukuPkgId,
            useShizuku = useShizuku,
            isInstalled = managerId != null,
            isCompatible = shizukuManager.isCompatible(),
            alsoHasRoot = useRoot,
        )

        if (useShizuku != true) return@combine flowOf(baseState)

        combine(
            // Just tie the lifecycle of the requester to the state's subscribers
            permissionRequester,
            shizukuManager.permissionState.map { }.onStart { emit(Unit) },
            shizukuManager.shizukuBinder.onStart { emit(null) },
        ) { _, _, server ->
            @Suppress("USELESS_CAST")
            baseState.copy(
                basicService = server != null,
                serviceState = shizukuManager.getServiceState(),
            ) as SetupModule.State
        }
    }
        .flatMapLatest { it }
        .onEach { if (it is Result) lastResult = it }
        .onStart {
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

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = rngString
    }

    suspend fun toggleUseShizuku(useShizuku: Boolean?) {
        log(TAG) { "toggleUseShizuku(useShizuku=$useShizuku)" }
        // Drop any cached state so we don't replay a stale Result for the previous setting.
        lastResult = null
        val couldUseShizuku = shizukuManager.useShizuku.first()
        if (useShizuku == true && shizukuManager.isGranted() == false) {
            log(TAG) { "Requesting permission" }
            // Unbounded on purpose: the answer arrives only once the user decided, and one that
            // arrives after we stopped waiting would be dropped.
            val grantResult = shizukuManager.requestPermission()

            log(TAG) { "Permission grant result was $grantResult" }
            adbSettings.useShizuku.value(if (grantResult == AdbPermissionState.Granted) true else null)
        } else {
            adbSettings.useShizuku.value(useShizuku)
        }

        if (!couldUseShizuku && useShizuku == true) {
            // Wait for the server connection to show up instead of guessing with a fixed delay.
            withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) {
                shizukuManager.shizukuBinder.filter { it != null }.first()
            } ?: log(TAG, WARN) { "ADB server did not connect within ${SERVICE_BIND_TIMEOUT_MS}ms" }
        }
    }

    data class Loading(
        override val startAt: Instant = Clock.System.now(),
    ) : SetupModule.State.Loading {
        override val type: SetupModule.Type = SetupModule.Type.SHIZUKU
    }

    data class Result(
        val pkg: Pkg.Id,
        val useShizuku: Boolean?,
        val isCompatible: Boolean = false,
        override val isInstalled: Boolean = false,
        val basicService: Boolean = false,
        val serviceState: ShizukuServiceState = ShizukuServiceState.NotChecked,
        val alsoHasRoot: Boolean = false,
    ) : SetupModule.State.Current {

        val ourService: Boolean
            get() = serviceState is ShizukuServiceState.Available

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
    }
}