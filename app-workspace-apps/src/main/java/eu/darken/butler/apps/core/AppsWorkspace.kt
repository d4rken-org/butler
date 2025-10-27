package eu.darken.butler.apps.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.engine.AppsEngine
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class AppsWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Workspace.Arguments?,
    dispatcherProvider: DispatcherProvider,
    appsEngineFactory: AppsEngine.Factory,
    private val appsSettings: AppsSettings,
) : Workspace {

    private val tag = logTag("Apps", "Workspace", id.shortTag)

    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    val appsEngine = appsEngineFactory.create(id, scope)

    override val type: Workspace.Type = Workspace.Type.APPS

    private val _state = DynamicStateFlow<State>(parentScope = scope) { State() }
    val state: Flow<State> = _state.flow

    data class State(
        val appsState: AppsState = AppsState(),
    )

    override val info: Flow<Workspace.Info> = _state.flow.map { state ->
        Workspace.Info(
            id = id,
            type = type,
            title = when {
                Bugs.isDebug -> "Apps ${id.shortTag}".toCaString()
                else -> R.string.apps_title.toCaString()
            },
            subtitle = R.string.apps_subtitle.toCaString(),
            operationCount = 0,
            attentionCount = 0,
            callerWorkspaceId = null,
        )
    }

    init {
        log(tag, INFO) { "AppsWorkspace initialized: $id" }

        // Load initial filter/sort settings
        scope.launch {
            try {
                val filterConfig = appsSettings.defaultFilterConfig.value()
                val sortMode = appsSettings.defaultSortMode.value()

                log(tag) { "Loaded settings: filterConfig=$filterConfig, sortMode=$sortMode" }

                appsEngine.updateFilterConfig(filterConfig)
                appsEngine.updateSortMode(sortMode)
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to load settings: $e" }
            }
        }

        // Monitor engine state
        appsEngine.state
            .onEach { engineState ->
                _state.updateBlocking {
                    copy(appsState = engineState)
                }
            }
            .launchIn(scope)
    }

    override suspend fun release() {
        log(tag, INFO) { "Releasing AppsWorkspace: $id" }
        scope.cancel()
    }

    @Parcelize
    data class Arguments(
        val placeholder: String? = null,
    ) : Workspace.Arguments {
        override val type: Workspace.Type get() = Workspace.Type.APPS
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Workspace.Arguments?): AppsWorkspace
    }
}
