package eu.darken.butler.setup.core.shizuku

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.rngString
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.setup.core.SetupModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class ShizukuSetupModule @Inject constructor(
    @param:AppScope private val appScope: CoroutineScope,
    private val adbSettings: AdbSettings,
    private val shizukuManager: ShizukuManager,
    rootManager: RootManager,
) : SetupModule {

    private val refreshTrigger = MutableStateFlow(rngString)

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
        val baseState = Result(
            pkg = shizukuManager.shizukuPkgId,
            useShizuku = useShizuku,
            isInstalled = shizukuManager.isInstalled(),
            isCompatible = shizukuManager.isCompatible(),
            alsoHasRoot = useRoot,
        )

        if (useShizuku != true) return@combine flowOf(baseState)

        combine(
            // Just tie the lifecycle of the requester to the state's subscribers
            permissionRequester,
            shizukuManager.permissionGrantEvents.map { }.onStart { emit(Unit) },
            shizukuManager.shizukuBinder.onStart { emit(null) },
        ) { _, _, binder ->
            @Suppress("USELESS_CAST")
            baseState.copy(
                basicService = binder?.pingBinder() ?: false,
                ourService = shizukuManager.isOurServiceAvailable(),
            ) as SetupModule.State
        }
    }
        .flatMapLatest { it }
        .onStart { emit(Loading()) }
        .onEach { log(TAG) { "New Shizuku setup state: $it" } }
        .replayingShare(appScope)

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = rngString
    }

    suspend fun toggleUseShizuku(useShizuku: Boolean?) {
        log(TAG) { "toggleUseShizuku(useShizuku=$useShizuku)" }
        val couldUseShizuku = shizukuManager.useShizuku.first()
        if (useShizuku == true && shizukuManager.isGranted() == false) {
            val grantResult = coroutineScope {
                val eventResult = async {
                    shizukuManager.permissionGrantEvents
                        .mapLatest { shizukuManager.isGranted() }
                        .first()
                }

                log(TAG) { "Requesting permission" }
                shizukuManager.requestPermission()

                withTimeoutOrNull(30 * 1000) { eventResult.await() }
            }

            log(TAG) { "Permission grant result was $grantResult" }
            adbSettings.useShizuku.value(grantResult.takeIf { it == true })
        } else {
            adbSettings.useShizuku.value(useShizuku)
        }

        if (!couldUseShizuku && useShizuku == true) {
            // TODO find a smarter way to do this, i.e. by waiting for a specific event.
            // Small delay to allow Shizuku service to bind
            delay(1500)
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
        val isInstalled: Boolean = false,
        val basicService: Boolean = false,
        val ourService: Boolean = false,
        val alsoHasRoot: Boolean = false,
    ) : SetupModule.State.Current {

        override val type: SetupModule.Type = SetupModule.Type.SHIZUKU

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
    }
}