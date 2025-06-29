package eu.darken.butler.setup.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.setup.core.root.RootSetupModule
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupManager @Inject constructor(
    private val setupRepository: SetupRepository,
) {

    val setupItems: Flow<List<SetupItem>> = setupRepository.modules
        .map { moduleStates ->
            SetupModule.Type.values().mapNotNull { type ->
                val state = moduleStates[type]
                if (state != null) {
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
        log(TAG) { "refresh()" }
        setupRepository.refresh()
    }

    suspend fun executeAction(type: SetupModule.Type, action: SetupAction) {
        log(TAG) { "executeAction(type=$type, action=$action)" }
        val module = setupRepository.getModule(type)
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