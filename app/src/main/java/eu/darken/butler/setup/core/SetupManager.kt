package eu.darken.butler.setup.core

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
import eu.darken.butler.setup.ui.SetupScreenOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupManager @Inject constructor(
    @param:AppScope private val appScope: CoroutineScope,
    private val setupModules: Set<@JvmSuppressWildcards SetupModule>,
) {

    private val options = MutableStateFlow(SetupScreenOptions())

    fun setOptions(newOptions: SetupScreenOptions) {
        log(TAG) { "setOptions($newOptions)" }
        options.value = newOptions
    }

    private val modules: Flow<Map<SetupModule.Type, SetupModule.State>> = combine(
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

    val setupItems: Flow<List<SetupItem>> = combine(
        modules,
        options
    ) { moduleStates, currentOptions ->
        SetupModule.Type.values().mapNotNull { type ->
            // Filter by typeFilter if provided
            if (currentOptions.typeFilter != null && type !in currentOptions.typeFilter) {
                return@mapNotNull null
            }
                val state = moduleStates[type]
                if (state != null) {
                    // Filter by showCompleted if set to false
                    if (!currentOptions.showCompleted && state is SetupModule.State.Current && state.isComplete) {
                        return@mapNotNull null
                    }
                    
                    SetupItem(
                        type = type,
                        state = state,
                        isRequired = isRequired(type),
                        priority = getPriority(type),
                    )
                } else {
                    log(TAG, WARN) { "No state found for setup type: $type" }
                    null
                }
            }.sortedBy { it.priority }
    }

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

    suspend fun executeAction(type: SetupModule.Type, action: SetupAction) {
        log(TAG) { "executeAction(type=$type, action=$action)" }
        val module = getModule(type)
        if (module == null) {
            log(TAG, WARN) { "No module found for type: $type" }
            return
        }

        when (action) {
            SetupAction.REFRESH -> module.refresh()
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
    }

    private fun isRequired(type: SetupModule.Type): Boolean = when (type) {
        SetupModule.Type.STORAGE -> true
        SetupModule.Type.SAF -> true
        else -> false
    }

    private fun getPriority(type: SetupModule.Type): Int = when (type) {
        SetupModule.Type.STORAGE -> 1
        SetupModule.Type.SAF -> 2
        SetupModule.Type.NOTIFICATION -> 3
        SetupModule.Type.USAGE_STATS -> 4
        SetupModule.Type.ROOT -> 5
        SetupModule.Type.SHIZUKU -> 6
        SetupModule.Type.INVENTORY -> 7
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

data class SetupItem(
    val type: SetupModule.Type,
    val state: SetupModule.State,
    val isRequired: Boolean,
    val priority: Int,
)

sealed interface SetupAction {
    object REFRESH : SetupAction
    data class TOGGLE_ROOT(val useRoot: Boolean?) : SetupAction
    data class TOGGLE_SHIZUKU(val useShizuku: Boolean?) : SetupAction
}