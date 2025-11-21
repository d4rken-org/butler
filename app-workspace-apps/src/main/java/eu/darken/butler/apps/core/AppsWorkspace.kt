package eu.darken.butler.apps.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.arguments.AppsArguments
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
import eu.darken.butler.workspace.core.WorkspaceFactory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

class AppsWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: AppsArguments,
    dispatcherProvider: DispatcherProvider,
    appsEngineFactory: AppsEngine.Factory,
    private val appsSettings: AppsSettings,
) : Workspace<AppsArguments> {

    private val tag = logTag("Apps", "Workspace", id.shortTag)

    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    val appsEngine = appsEngineFactory.create(id, scope)

    override val type: Workspace.Type = Workspace.Type.APPS

    override suspend fun createArguments(): AppsArguments {
        return creationArguments
    }

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
                val sortSettings = appsSettings.defaultSortSettings.value()

                log(tag) { "Loaded settings: filterConfig=$filterConfig, sortSettings=$sortSettings" }

                appsEngine.updateFilterConfig(filterConfig)
                appsEngine.updateSortSettings(sortSettings)
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

    @AssistedFactory
    interface Factory : WorkspaceFactory<AppsArguments> {
        override fun create(id: Workspace.Id, arguments: AppsArguments): AppsWorkspace

        override fun serialize(json: Json, arguments: AppsArguments): JsonElement {
            return json.encodeToJsonElement<AppsArguments>(arguments)
        }

        override fun deserialize(json: Json, element: JsonElement): AppsArguments {
            return json.decodeFromJsonElement<AppsArguments>(element)
        }
    }
}
