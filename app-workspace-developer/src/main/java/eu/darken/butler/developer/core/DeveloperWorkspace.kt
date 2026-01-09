package eu.darken.butler.developer.core

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
import eu.darken.butler.developer.R
import eu.darken.butler.developer.core.arguments.DeveloperArguments
import eu.darken.butler.developer.core.operations.DeveloperCommand
import eu.darken.butler.developer.core.operations.GenerateLargeFilesOperation
import eu.darken.butler.developer.core.operations.GenerateNestedStructureOperation
import eu.darken.butler.developer.core.operations.GenerateTextFilesOperation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import eu.darken.butler.workspace.core.operations.withStateUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement


class DeveloperWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: DeveloperArguments,
    dispatcherProvider: DispatcherProvider,
    private val operationsManager: OperationsManager,
    private val developerLogRepo: DeveloperLogRepo,
    private val generateLargeFilesFactory: GenerateLargeFilesOperation.Factory,
    private val generateNestedStructureFactory: GenerateNestedStructureOperation.Factory,
    private val generateTextFilesFactory: GenerateTextFilesOperation.Factory,
) : Workspace<DeveloperArguments> {

    private val tag = logTag("Developer", "Workspace", id.shortTag)
    private val scope = CoroutineScope(
        dispatcherProvider.IO +
            CoroutineName(tag) +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
            }
    )

    override val type: Workspace.Type = Workspace.Type.DEVELOPER

    override suspend fun createArguments(): DeveloperArguments {
        return creationArguments
    }

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = caString {
                val base = StringBuilder(it.getString(R.string.developer_workspace_tab_title))
                if (Bugs.isDebug) base.append(" " + id.shortTag)
                base.toString()
            },
            lifecycleState = Workspace.LifecycleState.Ready,
        )
    )

    init {
        log(tag, INFO) { "Initialized" }
        scope.launch { developerLogRepo.install() }

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
        developerLogRepo.uninstall()
        scope.cancel()
    }

    data class OperationsState(
        val operations: List<ManagedOperation> = emptyList(),
    )

    val operations: Flow<OperationsState> = operationsManager.operationsForWorkspace(id)
        .withStateUpdates()
        .map { ops -> OperationsState(operations = ops) }

    suspend fun execute(command: DeveloperCommand): Operation.Id {
        log(tag) { "execute(): $command" }
        val operation = when (command) {
            is DeveloperCommand.GenerateLargeFiles -> generateLargeFilesFactory.create(
                workspaceId = id,
                command = command,
            )
            is DeveloperCommand.GenerateNestedStructure -> generateNestedStructureFactory.create(
                workspaceId = id,
                command = command,
            )
            is DeveloperCommand.GenerateTextFiles -> generateTextFilesFactory.create(
                workspaceId = id,
                command = command,
            )
        }
        return operationsManager.submit(operation)
    }

    fun cancelOperation(operationId: Operation.Id) {
        scope.launch { operationsManager.cancel(operationId) }
    }

    fun dismissOperation(operationId: Operation.Id) {
        scope.launch { operationsManager.remove(operationId) }
    }

    fun clearCompletedOperations() {
        scope.launch { operationsManager.clearCompleted() }
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<DeveloperArguments> {

        override fun create(id: Workspace.Id, arguments: DeveloperArguments): DeveloperWorkspace

        override fun serialize(json: Json, arguments: DeveloperArguments): JsonElement {
            return json.encodeToJsonElement<DeveloperArguments>(arguments)
        }

        override fun deserialize(json: Json, element: JsonElement): DeveloperArguments {
            return json.decodeFromJsonElement<DeveloperArguments>(element)
        }
    }
}
