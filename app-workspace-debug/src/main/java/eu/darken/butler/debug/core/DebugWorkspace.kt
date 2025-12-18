package eu.darken.butler.debug.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.debug.R
import eu.darken.butler.debug.core.arguments.DebugArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement


class DebugWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: DebugArguments,
    dispatcherProvider: DispatcherProvider,
    private val operationsManager: OperationsManager,
    private val debugLogRepo: DebugLogRepo,
) : Workspace<DebugArguments> {

    private val tag = logTag("Debug", "Workspace", id.shortTag)
    private val scope = CoroutineScope(
        dispatcherProvider.IO +
            CoroutineName(tag) +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
            }
    )

    override val type: Workspace.Type = Workspace.Type.DEBUG

    override suspend fun createArguments(): DebugArguments {
        return creationArguments
    }

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = caString {
                val base = StringBuilder(it.getString(R.string.debug_workspace_tab_title))
                if (Bugs.isDebug) base.append(" " + id.shortTag)
                base.toString()
            },
        )
    )

    init {
        log(tag, INFO) { "Initialized" }
        scope.launch { debugLogRepo.install() }

        operationsManager.operationsForWorkspace(id).withOnlyStateChanges()
            .onEach { operations ->
                var operationCount = 0
                var attentionCount = 0

                operations.forEach { operation ->
                    when (val state = operation.state.value) {
                        is Operation.State.Queued -> operationCount++
                        is Operation.State.Active -> operationCount++
                        is Operation.State.Waiting -> {
                            operationCount++
                            attentionCount++
                        }
                        is Operation.State.Completed -> {
                            if (state.error != null && state.error !is CancellationException) {
                                attentionCount++
                            }
                        }
                    }
                }

                info.value = info.value.copy(
                    operationCount = operationCount,
                    attentionCount = attentionCount
                )
                log(tag, VERBOSE) { "Updated operation counts: active=$operationCount, attention=$attentionCount" }
            }
            .launchIn(scope)
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        debugLogRepo.uninstall()
        scope.cancel()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<DebugArguments> {

        override fun create(id: Workspace.Id, arguments: DebugArguments): DebugWorkspace

        override fun serialize(json: Json, arguments: DebugArguments): JsonElement {
            return json.encodeToJsonElement<DebugArguments>(arguments)
        }

        override fun deserialize(json: Json, element: JsonElement): DebugArguments {
            return json.decodeFromJsonElement<DebugArguments>(element)
        }
    }
}
