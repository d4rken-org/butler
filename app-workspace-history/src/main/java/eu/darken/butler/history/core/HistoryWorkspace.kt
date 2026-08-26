package eu.darken.butler.history.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.history.R
import eu.darken.butler.workspace.contracts.history.HistoryArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceDisplay
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.core.stateInWorkspace
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Multi-instance workspace presenting the global Operation History under a per-tab [HistoryFilter].
 * Multiple tabs can be open simultaneously, each scoped differently (unfiltered / failed only /
 * scoped to a path / etc.). Filter mutations come from the ViewModel via [setFilter] and are
 * captured in [createArguments] for session restore.
 */
class HistoryWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: HistoryArguments,
    dispatcherProvider: DispatcherProvider,
) : Workspace<HistoryArguments> {

    private val tag = logTag("History", "Workspace", id.shortTag)
    private val scope = CoroutineScope(
        dispatcherProvider.IO +
            CoroutineName(tag) +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
            }
    )

    override val type: Workspace.Type = Workspace.Type.HISTORY

    /** Authoritative filter state for this tab. UI mirrors this; restore reads it. */
    private val filterFlow = MutableStateFlow(creationArguments.filter)
    val filter: Flow<HistoryFilter> = filterFlow

    fun setFilter(newFilter: HistoryFilter) {
        log(tag) { "setFilter($newFilter)" }
        filterFlow.value = newFilter
    }

    /**
     * Atomic read-modify-write so concurrent toggles from rapid UI taps cannot lose updates.
     */
    fun updateFilter(transform: (HistoryFilter) -> HistoryFilter) {
        filterFlow.update { current ->
            val next = transform(current)
            log(tag) { "updateFilter: $current -> $next" }
            next
        }
    }

    override suspend fun createArguments(): HistoryArguments = HistoryArguments.Default(
        filter = filterFlow.value,
    )

    /** The filter [createArguments] reports; the info names the tab, not its filter. */
    override val restorableStateFingerprint: Any?
        get() = filterFlow.value

    // Same derivation the factory hands the paused stand-in, so both name this tab identically
    private val seedDisplay = deriveHistoryDisplay(creationArguments)

    override val info: StateFlow<Workspace.Info> = filterFlow.map { current ->
        Workspace.Info(
            id = id,
            type = type,
            title = derivedTitle(current),
            subtitle = R.string.history_workspace_subtitle.toCaString(),
            lifecycleState = Workspace.LifecycleState.Ready,
        )
    }.stateInWorkspace(
        scope = scope,
        initial = initialInfo(
            title = seedDisplay.title ?: type.label,
            subtitle = seedDisplay.subtitle,
            arguments = creationArguments,
        ),
    )

    init {
        log(tag, INFO) { "Initialized with filter ${creationArguments.filter}" }
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        scope.cancel()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<HistoryArguments> {
        override fun create(id: Workspace.Id, arguments: HistoryArguments): HistoryWorkspace

        override val argumentsSerializer: KSerializer<HistoryArguments> get() = serializer()

        override fun deriveDisplay(arguments: HistoryArguments): WorkspaceDisplay =
            deriveHistoryDisplay(arguments)
    }

    companion object {
        internal fun derivedTitle(filter: HistoryFilter) = caString { ctx ->
            val onlyPath = filter.outcomes.isEmpty() && filter.kinds.isEmpty() && filter.pathScopes.isNotEmpty()
            when {
                filter.isUnfiltered -> ctx.getString(R.string.history_workspace_title)
                onlyPath && filter.pathScopes.size == 1 ->
                    ctx.getString(R.string.history_workspace_title_scoped, filter.pathScopes.first())
                onlyPath ->
                    ctx.resources.getQuantityString(
                        R.plurals.history_workspace_title_scoped_many,
                        filter.pathScopes.size,
                        filter.pathScopes.size,
                    )
                filter.outcomes.size == 1 && filter.kinds.isEmpty() && filter.pathScopes.isEmpty() ->
                    when (filter.outcomes.first()) {
                        HistoryOutcome.COMPLETED -> ctx.getString(R.string.history_workspace_title_outcome_completed)
                        HistoryOutcome.PARTIAL -> ctx.getString(R.string.history_workspace_title_outcome_partial)
                        HistoryOutcome.FAILED -> ctx.getString(R.string.history_workspace_title_outcome_failed)
                        HistoryOutcome.CANCELLED -> ctx.getString(R.string.history_workspace_title_outcome_cancelled)
                    }
                filter.kinds.size == 1 && filter.outcomes.isEmpty() && filter.pathScopes.isEmpty() ->
                    when (filter.kinds.first()) {
                        Operation.Metadata.Kind.COPY -> ctx.getString(R.string.history_workspace_title_kind_copy)
                        Operation.Metadata.Kind.MOVE -> ctx.getString(R.string.history_workspace_title_kind_move)
                        Operation.Metadata.Kind.DELETE -> ctx.getString(R.string.history_workspace_title_kind_delete)
                        Operation.Metadata.Kind.CREATE_FILE -> ctx.getString(R.string.history_workspace_title_kind_create_file)
                        Operation.Metadata.Kind.CREATE_FOLDER -> ctx.getString(R.string.history_workspace_title_kind_create_folder)
                        Operation.Metadata.Kind.SAVE -> ctx.getString(R.string.history_workspace_title_kind_save)
                        Operation.Metadata.Kind.COMPRESS -> ctx.getString(R.string.history_workspace_title_kind_compress)
                        Operation.Metadata.Kind.EXTRACT -> ctx.getString(R.string.history_workspace_title_kind_extract)
                        Operation.Metadata.Kind.RESTORE -> ctx.getString(R.string.history_workspace_title_kind_restore)
                        Operation.Metadata.Kind.INSTALL -> ctx.getString(R.string.history_workspace_title_kind_install)
                    }
                else -> ctx.getString(R.string.history_workspace_title_filtered)
            }
        }
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.HISTORY)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
