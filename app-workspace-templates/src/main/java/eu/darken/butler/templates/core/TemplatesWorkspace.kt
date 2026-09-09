package eu.darken.butler.templates.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.templates.R
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.toOperationCounts
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer


class TemplatesWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: TemplatesArguments,
    dispatcherProvider: DispatcherProvider,
    private val operationsManager: OperationsManager,
) : Workspace<TemplatesArguments> {

    private val tag = logTag("Templates", "Workspace", id.shortTag)
    private val scope = CoroutineScope(
        dispatcherProvider.IO +
            CoroutineName(tag) +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
                // Logged only: the Templates workspace has no failure UI because listing templates
                // is non-destructive and recovers on the next emission. Revisit if a user-facing
                // error state becomes necessary.
            }
    )

    override val type: Workspace.Type = Workspace.Type.TEMPLATES

    override suspend fun createArguments(): TemplatesArguments {
        return creationArguments
    }

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = caString {
                val base = StringBuilder(it.getString(R.string.workspace_templates_tab_title))
                if (Bugs.isDebug) base.append(" " + id.shortTag)
                base.toString()
            },
            lifecycleState = Workspace.LifecycleState.Ready,
        )
    )

    init {
        log(tag, INFO) { "Initialized" }

        // Track operation counts for this workspace
        operationsManager.operationsForWorkspace(id).withOnlyStateChanges()
            .onEach { operations ->
                val counts = operations.toOperationCounts()
                info.value = info.value.copy(
                    operationCount = counts.unfinished,
                    activeCount = counts.active,
                    attentionCount = counts.attention,
                )
                log(tag, VERBOSE) { "Updated operation counts: $counts" }
            }
            .launchIn(scope)
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        scope.cancel()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<TemplatesArguments> {

        override fun create(id: Workspace.Id, arguments: TemplatesArguments): TemplatesWorkspace

        override val argumentsSerializer: KSerializer<TemplatesArguments> get() = serializer()
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.TEMPLATES)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
