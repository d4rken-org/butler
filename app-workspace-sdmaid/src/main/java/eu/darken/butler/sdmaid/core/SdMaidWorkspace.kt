package eu.darken.butler.sdmaid.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.sdmaid.R
import eu.darken.butler.sdmaid.core.arguments.SdMaidArguments
import eu.darken.butler.sdmaid.core.ipc.SdMaidAvailability
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

class SdMaidWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: SdMaidArguments,
    dispatcherProvider: DispatcherProvider,
    private val sdMaidAvailability: SdMaidAvailability,
) : Workspace<SdMaidArguments> {

    private val tag = logTag("SDMaid", "Workspace", id.shortTag)

    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    override val type: Workspace.Type = Workspace.Type.SDMAID

    override suspend fun createArguments(): SdMaidArguments {
        val state = _state.value()
        return SdMaidArguments.Default(
            initialTool = state.currentTool,
        )
    }

    sealed interface ConnectionState {
        data object Checking : ConnectionState
        data object NotInstalled : ConnectionState
        data object ServiceUnavailable : ConnectionState
        data object Connecting : ConnectionState
        data class Connected(val version: String) : ConnectionState
        data class Error(val error: Throwable) : ConnectionState
    }

    data class State(
        val connectionState: ConnectionState = ConnectionState.Checking,
        val currentTool: SdMaidArguments.ToolType? = null,
    )

    private val _state = DynamicStateFlow<State>(parentScope = scope) {
        State(
            currentTool = (creationArguments as? SdMaidArguments.Default)?.initialTool
        )
    }
    val state: Flow<State> = _state.flow

    override val info: Flow<Workspace.Info> = _state.flow.map { state ->
        Workspace.Info(
            id = id,
            type = type,
            title = when {
                Bugs.isDebug -> "SD Maid ${id.shortTag}".toCaString()
                else -> R.string.sdmaid_workspace_title.toCaString()
            },
            subtitle = when (state.connectionState) {
                is ConnectionState.NotInstalled -> R.string.sdmaid_status_not_installed.toCaString()
                is ConnectionState.ServiceUnavailable -> R.string.sdmaid_status_service_unavailable.toCaString()
                is ConnectionState.Connecting -> R.string.sdmaid_status_connecting.toCaString()
                is ConnectionState.Connected -> R.string.sdmaid_status_connected.toCaString()
                is ConnectionState.Error -> R.string.sdmaid_status_error.toCaString()
                is ConnectionState.Checking -> null
            },
            lifecycleState = Workspace.LifecycleState.Ready,
            operationCount = 0,
            attentionCount = 0,
            callerWorkspaceId = null,
        )
    }

    init {
        log(tag, INFO) { "SdMaidWorkspace initialized: $id" }

        // Monitor SD Maid availability
        sdMaidAvailability.state
            .onEach { availability ->
                log(tag) { "SD Maid availability changed: $availability" }
                _state.updateBlocking {
                    copy(
                        connectionState = when {
                            !availability.isInstalled -> ConnectionState.NotInstalled
                            !availability.isServiceAvailable -> ConnectionState.ServiceUnavailable
                            availability.canConnect -> {
                                // For now, just mark as "connected" if available
                                // Real IPC connection will be added in Phase 3
                                ConnectionState.Connected(availability.installedVersion ?: "unknown")
                            }
                            else -> ConnectionState.ServiceUnavailable
                        }
                    )
                }
            }
            .launchIn(scope)
    }

    fun selectTool(tool: SdMaidArguments.ToolType?) {
        log(tag) { "selectTool($tool)" }
        _state.updateAsync {
            copy(currentTool = tool)
        }
    }

    fun retry() {
        log(tag) { "retry()" }
        // For now, just re-check availability
        // Real reconnection logic will be added in Phase 3
        _state.updateAsync {
            copy(connectionState = ConnectionState.Checking)
        }
    }

    override suspend fun release() {
        log(tag, INFO) { "Releasing SdMaidWorkspace: $id" }
        scope.cancel()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<SdMaidArguments> {
        override fun create(id: Workspace.Id, arguments: SdMaidArguments): SdMaidWorkspace

        override fun serialize(json: Json, arguments: SdMaidArguments): JsonElement {
            return json.encodeToJsonElement<SdMaidArguments>(arguments)
        }

        override fun deserialize(json: Json, element: JsonElement): SdMaidArguments {
            return json.decodeFromJsonElement<SdMaidArguments>(element)
        }
    }
}
