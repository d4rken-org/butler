package eu.darken.butler.bugreport.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.bugreport.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.contracts.bugreport.BugReportArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.stateInWorkspace
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Singleton workspace listing locally-stored bug reports. Stateless beyond its identity — the report
 * list comes from [eu.darken.butler.common.debug.bugreport.BugReportRepo] in the ViewModel.
 */
class BugReportWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: BugReportArguments,
    dispatcherProvider: DispatcherProvider,
) : Workspace<BugReportArguments> {

    private val tag = logTag("BugReport", "Workspace", id.shortTag)
    private val scope = CoroutineScope(
        dispatcherProvider.IO +
            CoroutineName(tag) +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
            }
    )

    override val type: Workspace.Type = Workspace.Type.BUG_REPORT

    override suspend fun createArguments(): BugReportArguments = BugReportArguments.Default()

    override val info: StateFlow<Workspace.Info> = MutableStateFlow(Unit).map {
        Workspace.Info(
            id = id,
            type = type,
            title = R.string.bugreport_workspace_title.toCaString(),
            subtitle = R.string.bugreport_workspace_subtitle.toCaString(),
            lifecycleState = Workspace.LifecycleState.Ready,
        )
    }.stateInWorkspace(
        scope = scope,
        initial = initialInfo(
            title = R.string.bugreport_workspace_title.toCaString(),
            arguments = creationArguments,
        ),
    )

    init {
        log(tag, INFO) { "Initialized" }
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        scope.cancel()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<BugReportArguments> {
        override fun create(id: Workspace.Id, arguments: BugReportArguments): BugReportWorkspace

        override val argumentsSerializer: KSerializer<BugReportArguments> get() = serializer()
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.BUG_REPORT)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
