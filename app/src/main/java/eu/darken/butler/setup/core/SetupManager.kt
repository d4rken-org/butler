package eu.darken.butler.setup.core

import android.content.Intent
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.setup.core.inventory.InventorySetupModule
import eu.darken.butler.setup.core.notification.NotificationSetupModule
import eu.darken.butler.setup.core.root.RootSetupModule
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule
import eu.darken.butler.setup.core.storage.StorageSetupModule
import eu.darken.butler.setup.core.usagestats.UsageStatsSetupModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Singleton
class SetupManager @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    setupModules: Set<@JvmSuppressWildcards SetupModule>,
) {
    private val modulesByType: Map<SetupModule.Type, SetupModule> = setupModules.associateBy { it.type }
    private val refreshMutex = Mutex()
    private var lastRefreshTime: Instant = Instant.DISTANT_PAST

    val moduleStates: Flow<Map<SetupModule.Type, SetupModule.State>> = combine(
        modulesByType.values.map { it.state }
    ) { states ->
        modulesByType.values.zip(states).associate { (module, state) ->
            state.type to state
        }
    }
        .catch { e ->
            log(TAG, ERROR) { "Setup module state collection failed: ${e.asLog()}" }
            emit(emptyMap())
        }
        .distinctUntilChanged()
        .onEach { states ->
            log(TAG) { "Setup states updated: ${states.mapValues { "${it.key}=${it.value}" }}" }
        }
        .shareIn(
            scope = appScope,
            replay = 1,
            started = SharingStarted.Eagerly
        )

    suspend fun refresh() = refreshMutex.withLock {
        val now = Clock.System.now()
        val elapsed = now - lastRefreshTime
        if (elapsed < REFRESH_DEBOUNCE) {
            log(TAG) { "refresh() - debounced (${elapsed.inWholeMilliseconds}ms since last refresh)" }
            return@withLock
        }
        lastRefreshTime = now

        log(TAG) { "refresh() - refreshing ${modulesByType.size} modules" }
        modulesByType.values.forEach { module ->
            try {
                module.refresh()
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to refresh ${module::class.simpleName}: $e" }
            }
        }
    }

    suspend fun executeAction(type: SetupModule.Type, action: SetupAction): PermissionResult? {
        log(TAG) { "executeAction(type=$type, action=$action)" }
        val module = getModule(type)
        if (module == null) {
            log(TAG, WARN) { "No module found for type: $type" }
            return null
        }

        when (action) {
            SetupAction.Refresh -> module.refresh()
            SetupAction.RequestPermission -> {
                when (type) {
                    SetupModule.Type.STORAGE -> {
                        val storageModule = module as StorageSetupModule
                        val intent = storageModule.getPermissionIntent()
                        if (intent != null) {
                            return PermissionResult(intent = intent)
                        }
                        val runtimePerms = storageModule.getRuntimePermissions()
                        if (runtimePerms.isNotEmpty()) {
                            return PermissionResult(runtimePermissions = runtimePerms)
                        }
                        log(TAG, WARN) { "No permissions available for $type" }
                    }
                    SetupModule.Type.NOTIFICATION -> {
                        val notificationModule = module as NotificationSetupModule
                        val runtimePerms = notificationModule.getRuntimePermissions()
                        if (runtimePerms.isNotEmpty()) {
                            return PermissionResult(runtimePermissions = runtimePerms)
                        } else {
                            log(TAG) { "No runtime permissions needed for $type" }
                        }
                    }
                    SetupModule.Type.USAGE_STATS -> {
                        val usageStatsModule = module as UsageStatsSetupModule
                        val intent = usageStatsModule.getPermissionIntent()
                        if (intent != null) {
                            return PermissionResult(intent = intent)
                        } else {
                            log(TAG, WARN) { "No permission intent available for $type" }
                        }
                    }
                    SetupModule.Type.SHIZUKU -> log(TAG, WARN) { "RequestPermission not applicable for $type" }
                    SetupModule.Type.ROOT -> log(TAG, WARN) { "RequestPermission not applicable for $type" }
                    SetupModule.Type.INVENTORY -> log(TAG, WARN) { "RequestPermission not applicable for $type" }
                }
            }
            is SetupAction.ToggleRoot -> {
                val rootModule = module as? RootSetupModule
                rootModule?.toggleUseRoot(action.useRoot)
                    ?: log(TAG, WARN) { "Module for $type is not a RootSetupModule" }
            }
            is SetupAction.ToggleShizuku -> {
                val shizukuModule = module as? ShizukuSetupModule
                shizukuModule?.toggleUseShizuku(action.useShizuku)
                    ?: log(TAG, WARN) { "Module for $type is not a ShizukuSetupModule" }
            }
        }
        return null
    }

    internal fun getModule(type: SetupModule.Type): SetupModule? = modulesByType[type]

    companion object {
        private val TAG = logTag("Setup", "Manager")
        private val REFRESH_DEBOUNCE = 200.milliseconds
    }
}

sealed interface SetupAction {
    data object Refresh : SetupAction
    data object RequestPermission : SetupAction
    data class ToggleRoot(val useRoot: Boolean?) : SetupAction
    data class ToggleShizuku(val useShizuku: Boolean?) : SetupAction
}

data class PermissionResult(
    val intent: Intent? = null,
    val runtimePermissions: Set<String> = emptySet()
)