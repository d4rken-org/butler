package eu.darken.butler.setup.core

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.setup.SetupModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupRepository @Inject constructor(
    @param:AppScope private val appScope: CoroutineScope,
    private val setupModules: Set<@JvmSuppressWildcards SetupModule>,
) {

    val modules: Flow<Map<SetupModule.Type, SetupModule.State>> = combine(
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

    fun getModule(type: SetupModule.Type): SetupModule? {
        return setupModules.find { module ->
            // Get the current state to determine the type
            // Since we can't access the flow synchronously, we'll need to find by the module's type
            // Let's check if the module has a companion object with the type
            when (type) {
                SetupModule.Type.ROOT -> module is eu.darken.butler.setup.root.RootSetupModule
                SetupModule.Type.NOTIFICATION -> module is eu.darken.butler.setup.notification.NotificationSetupModule
                SetupModule.Type.USAGE_STATS -> module is eu.darken.butler.setup.usagestats.UsageStatsSetupModule
                SetupModule.Type.SHIZUKU -> module is eu.darken.butler.setup.shizuku.ShizukuSetupModule
                SetupModule.Type.SAF -> module is eu.darken.butler.setup.saf.SAFSetupModule
                SetupModule.Type.STORAGE -> module is eu.darken.butler.setup.storage.StorageSetupModule
                SetupModule.Type.INVENTORY -> module is eu.darken.butler.setup.inventory.InventorySetupModule
            }
        }
    }

    companion object {
        private val TAG = logTag("Setup", "Repository")
    }
}