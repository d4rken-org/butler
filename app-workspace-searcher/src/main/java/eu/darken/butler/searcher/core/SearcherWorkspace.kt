package eu.darken.butler.searcher.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import eu.darken.butler.workspace.core.preview.SearcherPreviewData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.parcelize.Parcelize


class SearcherWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Arguments?,
    dispatcherProvider: DispatcherProvider,
    private val operationsManager: OperationsManager,
) : Workspace {

    private val tag = logTag( "Searcher","Workspace", id.shortTag)
    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    override val type: Workspace.Type = Workspace.Type.SEARCHER

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = "Searcher ${id.shortTag}".toCaString(),
            previewData = SearcherPreviewData(),
        )
    )

    init {
        log(tag, INFO) { "Initialized" }

        // Track operation counts for this workspace
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
        scope.cancel()
    }

    @Parcelize
    data class Arguments(
        val startPath: APath<*>? = null,
    ) : Workspace.Arguments {
        override val type: Workspace.Type
            get() = Workspace.Type.SEARCHER
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Arguments?): SearcherWorkspace
    }
}