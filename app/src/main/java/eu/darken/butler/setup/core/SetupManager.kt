package eu.darken.butler.setup.core

import android.content.Intent
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.setup.core.inventory.InventorySetupModule
import eu.darken.butler.setup.core.notification.NotificationSetupModule
import eu.darken.butler.setup.core.root.RootSetupModule
import eu.darken.butler.setup.core.saf.SAFSetupModule
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule
import eu.darken.butler.setup.core.storage.StorageSetupModule
import eu.darken.butler.setup.core.usagestats.UsageStatsSetupModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupManager @Inject constructor(
    @param:AppScope private val appScope: CoroutineScope,
    private val setupModules: Set<@JvmSuppressWildcards SetupModule>,
) {

    val moduleStates: Flow<Map<SetupModule.Type, SetupModule.State>> = combine(
        setupModules.map { it.state }
    ) { states ->
        setupModules.zip(states).associate { (module, state) ->
            state.type to state
        }
    }
        .onEach { states ->
            log(TAG) { "Setup states updated: ${states.mapValues { "${it.key}=${it.value}" }}" }
        }
        .replayingShare(appScope)

    suspend fun refresh() {
        log(TAG) { "refresh() - refreshing ${setupModules.size} modules" }
        setupModules.forEach { module ->
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
            SetupAction.REFRESH -> module.refresh()
            SetupAction.REQUEST_PERMISSION -> {
                when (type) {
                    SetupModule.Type.STORAGE -> {
                        val storageModule = module as? StorageSetupModule
                        val intent = storageModule?.getPermissionIntent()
                        if (intent != null) {
                            return PermissionResult(intent = intent)
                        } else {
                            log(TAG, WARN) { "No permission intent available for $type" }
                        }
                    }
                    SetupModule.Type.NOTIFICATION -> {
                        val notificationModule = module as? NotificationSetupModule
                        val runtimePerms = notificationModule?.getRuntimePermissions() ?: emptySet()
                        if (runtimePerms.isNotEmpty()) {
                            return PermissionResult(runtimePermissions = runtimePerms)
                        } else {
                            log(TAG) { "No runtime permissions needed for $type" }
                        }
                    }
                    SetupModule.Type.USAGE_STATS -> {
                        val usageStatsModule = module as? UsageStatsSetupModule
                        val intent = usageStatsModule?.getPermissionIntent()
                        if (intent != null) {
                            return PermissionResult(intent = intent)
                        } else {
                            log(TAG, WARN) { "No permission intent available for $type" }
                        }
                    }
                    else -> {
                        log(TAG, WARN) { "REQUEST_PERMISSION not implemented for $type" }
                    }
                }
            }
            is SetupAction.TOGGLE_ROOT -> {
                val rootModule = module as? RootSetupModule
                rootModule?.toggleUseRoot(action.useRoot)
                    ?: log(TAG, WARN) { "Module for $type is not a RootSetupModule" }
            }
            is SetupAction.TOGGLE_SHIZUKU -> {
                val shizukuModule = module as? ShizukuSetupModule
                shizukuModule?.toggleUseShizuku(action.useShizuku)
                    ?: log(TAG, WARN) { "Module for $type is not a ShizukuSetupModule" }
            }
        }
        return null
    }

    private fun getModule(type: SetupModule.Type): SetupModule? {
        return setupModules.find { module ->
            when (type) {
                SetupModule.Type.ROOT -> module is RootSetupModule
                SetupModule.Type.NOTIFICATION -> module is NotificationSetupModule
                SetupModule.Type.USAGE_STATS -> module is UsageStatsSetupModule
                SetupModule.Type.SHIZUKU -> module is ShizukuSetupModule
                SetupModule.Type.SAF -> module is SAFSetupModule
                SetupModule.Type.STORAGE -> module is StorageSetupModule
                SetupModule.Type.INVENTORY -> module is InventorySetupModule
            }
        }
    }

    companion object {
        private val TAG = logTag("Setup", "Manager")
    }
}

sealed interface SetupAction {
    object REFRESH : SetupAction
    object REQUEST_PERMISSION : SetupAction
    data class TOGGLE_ROOT(val useRoot: Boolean?) : SetupAction
    data class TOGGLE_SHIZUKU(val useShizuku: Boolean?) : SetupAction
}

data class PermissionResult(
    val intent: Intent? = null,
    val runtimePermissions: Set<String> = emptySet()
)