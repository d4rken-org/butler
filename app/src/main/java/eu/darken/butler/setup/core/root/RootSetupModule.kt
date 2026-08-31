package eu.darken.butler.setup.core.root

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.ipc.IpcContract
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.rngString
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootSettings
import eu.darken.butler.setup.core.SetupModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class RootSetupModule @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val rootSettings: RootSettings,
    private val rootManager: RootManager,
) : SetupModule {

    override val type = SetupModule.Type.ROOT

    private val refreshTrigger = MutableStateFlow(rngString)

    /** Overridden in tests to keep the timeout case fast, never in production. */
    internal var connectTimeoutMs: Long = CONNECT_TIMEOUT_MS

    // Last known concrete Result, kept so re-subscription (e.g. returning to the dashboard) can emit it
    // immediately instead of regressing to Loading and flickering the setup card while the availability
    // probe re-runs (acquiring the root host can cold-bind a su session). Only ever holds a real Result.
    @Volatile
    private var lastResult: Result? = null

    override val state: Flow<SetupModule.State> = combine(refreshTrigger, rootSettings.useRoot.flow) { _, useRoot ->
        val baseState = Result(
            useRoot = useRoot,
            isInstalled = rootManager.isInstalled(),
        )

        if (useRoot != true) return@combine flowOf(baseState)

        channelFlow<SetupModule.State> {
            // Resolving the connection can cold-bind an su session, so there is a window in which we
            // know nothing yet. It is a connecting state, not a failed one.
            send(baseState.copy(serviceState = RootServiceState.Connecting))

            val timeout = launch {
                delay(connectTimeoutMs)
                log(TAG, WARN) { "Root service did not connect within ${connectTimeoutMs}ms" }
                send(baseState.copy(serviceState = RootServiceState.TimedOut))
            }

            rootManager.binder
                .map { connection ->
                    val serviceState = if (connection == null) {
                        RootServiceState.Failed
                    } else {
                        try {
                            // The round-trip is the liveness proof; the identity in its reply must still
                            // be the one the connection was gated on.
                            val ours = IpcContract.decode(connection.ipc.checkBase()) == connection.hostIdentity
                            if (ours) RootServiceState.Available else RootServiceState.Failed
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log(TAG, WARN) { "Error while checking for root: $e" }
                            RootServiceState.Failed
                        }
                    }

                    baseState.copy(serviceState = serviceState)
                }
                // The service client rejects a host left over from an older app installation, so obtaining
                // the binder itself can fail. Report "no service" rather than erroring the whole setup flow.
                .catch { e ->
                    log(TAG, WARN) { "Root service unavailable: $e" }
                    emit(baseState.copy(serviceState = RootServiceState.Failed))
                }
                .collect {
                    timeout.cancelAndJoin()
                    send(it)
                }
        }
    }
        .flatMapLatest { it }
        .onEach { if (it is Result) lastResult = it }
        .onStart {
            // Don't regress to Loading if we already know the result: emit the last known state so the
            // dashboard setup card doesn't flicker while the probe re-runs. Guard against a useRoot
            // change that happened while we had no subscribers.
            val cached = lastResult
            if (cached != null && cached.useRoot == rootSettings.useRoot.value()) {
                emit(cached)
            } else {
                emit(Loading())
            }
        }
        .onEach { log(TAG) { "New Root setup state: $it" } }
        .replayingShare(appScope)

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = rngString
    }

    suspend fun toggleUseRoot(useRoot: Boolean?) {
        log(TAG) { "toggleUseRoot(useRoot=$useRoot)" }
        // Drop any cached state so we don't replay a stale Result for the previous setting.
        lastResult = null
        rootSettings.useRoot.value(useRoot)
    }

    data class Loading(
        override val startAt: Instant = Clock.System.now(),
    ) : SetupModule.State.Loading {
        override val type: SetupModule.Type = SetupModule.Type.ROOT
    }

    data class Result(
        val useRoot: Boolean?,
        override val isInstalled: Boolean = false,
        val serviceState: RootServiceState = RootServiceState.NotChecked,
    ) : SetupModule.State.Current {

        val ourService: Boolean
            get() = serviceState is RootServiceState.Available

        override val type: SetupModule.Type = SetupModule.Type.ROOT

        override val isAvailable: Boolean
            get() = isInstalled

        override val isComplete: Boolean = when {
            useRoot == true -> ourService // Only complete if enabled AND connected
            useRoot == false -> true // Complete if explicitly disabled
            else -> false // Not complete if not configured
        }
    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: RootSetupModule): SetupModule
    }

    companion object {
        private val TAG = logTag("Setup", "Root", "Module")

        // Generous on purpose: acquiring the host can wait on a user answering an su prompt, and a false
        // timeout would report working root as unavailable.
        internal const val CONNECT_TIMEOUT_MS = 60 * 1000L
    }
}