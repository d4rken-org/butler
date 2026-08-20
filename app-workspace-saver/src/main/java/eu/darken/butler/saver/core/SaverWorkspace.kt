package eu.darken.butler.saver.core

import android.net.Uri
import androidx.core.net.toUri
import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.saver.core.operations.SaveFilesOperation
import eu.darken.butler.saver.core.operations.SaveFilesReport
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceDisplay
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.stateInWorkspace
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class SaverWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: SaverArguments,
    dispatcherProvider: DispatcherProvider,
    private val contentUriHelper: ContentUriHelper,
    private val operationsManager: OperationsManager,
    private val issueHandler: IssueHandler,
    private val saveFilesOperationFactory: SaveFilesOperation.Factory,
    private val pkgOps: PkgOps,
    private val json: Json,
    private val storageEnvironment: StorageEnvironment,
) : Workspace<SaverArguments> {

    private val tag = logTag("Saver", "Workspace", id.shortTag)

    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    override val type: Workspace.Type = Workspace.Type.SAVER

    /** Timestamp when this workspace (share intent) was created */
    val createdAt: Instant = Clock.System.now()

    private val creationArguments: SaverArguments.Default = arguments as SaverArguments.Default

    val sourceUris: List<Uri> = creationArguments.sourceUris.map { it.toUri() }

    val isBatchMode: Boolean get() = sourceUris.size > 1

    /**
     * Immutable modal marker: set when launched by another workspace (APK export). Read directly
     * from the creation arguments so it is correct synchronously, before the async state combine
     * publishes — unlike [state], whose lazy seed carries a null caller id.
     */
    val callerWorkspaceId: Workspace.Id? = creationArguments.callerWorkspaceId

    /** Whether the caller asked to be told where this Saver wrote, see [SaverArguments.Default]. */
    val reportSavedPaths: Boolean = creationArguments.reportSavedPaths

    // State flows for UI
    private val _sourceInfos = MutableStateFlow<List<ContentUriHelper.SourceInfo>>(emptyList())
    val sourceInfos: Flow<List<ContentUriHelper.SourceInfo>> = _sourceInfos

    private val _destination = MutableStateFlow<APath<*>?>(
        creationArguments.destinationPath ?: storageEnvironment.downloadsDirectory
    )
    val destination: Flow<APath<*>?> = _destination

    // Only used for single file mode
    private val _filename = MutableStateFlow("")
    val filename: Flow<String> = _filename

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: Flow<SaveState> = _saveState

    // Serializes the check-and-set below so a rapid double-tap can't submit two operations.
    private val saveMutex = Mutex()

    private val _currentOperationId = MutableStateFlow<Operation.Id?>(null)
    val currentOperation: Flow<ManagedOperation?> = _currentOperationId
        .flatMapLatest { opId ->
            if (opId == null) {
                flowOf(null)
            } else {
                operationsManager.operations
                    .map { ops -> ops.find { it.id == opId } }
                    .flatMapLatest { managedOp ->
                        if (managedOp == null) {
                            flowOf(null)
                        } else {
                            managedOp.state.map { managedOp }
                        }
                    }
            }
        }

    private val _callerLabel = MutableStateFlow<String?>(UNKNOWN_CALLER_LABEL)

    private val _state = DynamicStateFlow<State>(parentScope = scope) { State() }
    val state: Flow<State> = _state.flow

    sealed interface SaveState {
        data object Idle : SaveState
        data class Saving(
            val currentFile: Int,
            val totalFiles: Int,
            val currentFilename: String,
        ) : SaveState

        data class Success(val report: SaveFilesReport) : SaveState
        data class Error(val error: Throwable) : SaveState
    }

    data class State(
        val sourceInfos: List<ContentUriHelper.SourceInfo> = emptyList(),
        val destination: APath<*>? = null,
        val filename: String = "",
        val saveState: SaveState = SaveState.Idle,
        val callerLabel: String? = null,
        val callerPackage: Pkg.Id? = null,
        val createdAt: Instant? = null,
        /** Set when launched by another workspace (modal export); drives modal UI + close behavior. */
        val callerWorkspaceId: Workspace.Id? = null,
    ) {
        val isBatchMode: Boolean get() = sourceInfos.size > 1
        val fileCount: Int get() = sourceInfos.size
        val totalSize: Long? get() = sourceInfos.mapNotNull { it.size }.takeIf { it.size == sourceInfos.size }?.sum()
        val hasInaccessibleFiles: Boolean get() = sourceInfos.any { !it.isAccessible }
    }

    // Same derivation the factory hands the paused stand-in, so both name this tab identically
    private val seedDisplay = deriveSaverDisplay(creationArguments)

    override val info: StateFlow<Workspace.Info> = combine(
        _sourceInfos,
        _filename,
        _saveState,
        _destination,
    ) { sourceInfos, filename, saveState, destination ->
        val operationCount = when (saveState) {
            is SaveState.Saving -> 1
            else -> 0
        }
        val attentionCount = when {
            saveState is SaveState.Error -> 1
            sourceInfos.any { !it.isAccessible } -> 1
            else -> 0
        }

        Workspace.Info(
            id = id,
            type = type,
            // Sources are extracted asynchronously; until they land, the count from the arguments
            // is the identity the paused tab already showed - a debug label here would drop it
            title = saverTitle(sourceInfos.size.takeIf { it > 0 } ?: sourceUris.size),
            subtitle = when {
                sourceInfos.size > 1 -> sourceInfos.firstOrNull()?.displayName?.let { "$it, …" }?.toCaString()
                filename.isNotBlank() -> filename.toCaString()
                // The CURRENT destination, not the one this tab was created with: it is what a
                // session save persists, so a creation-time fallback would outlive its own truth
                else -> saverLocationSubtitle(destination, creationArguments.callerPackage)
            },
            lifecycleState = Workspace.LifecycleState.Ready,
            operationCount = operationCount,
            attentionCount = attentionCount,
            // The shared content only exists inside this tab until it is written somewhere: until
            // then, closing the tab discards what the user handed to Butler. Same reasoning as
            // [isPausable] below, said in the vocabulary the close paths understand.
            //
            // A finished save is not the same as a successful one: SaveFilesOperation collects
            // per-file failures into the report and still completes, so a run that saved nothing at
            // all arrives here as Success. Those sources are still only in this tab. Skipped files
            // are not counted - the user chose to drop those.
            hasUnsavedChanges = saveState !is SaveState.Success || saveState.report.errors.isNotEmpty(),
            // A transient export flow: filename edits and save progress live only in this instance,
            // never in the arguments, so releasing it would silently drop the user's export.
            isPausable = false,
            callerWorkspaceId = creationArguments.callerWorkspaceId,
            modalPresentation = creationArguments.modalPresentation,
        )
    }.stateInWorkspace(
        scope = scope,
        // The seed is what the close paths read synchronously before the first combine emission, so
        // it has to state the tab's standing truth rather than the defaults: a Saver holds unsaved
        // content from the moment it exists, and never becomes pausable.
        initial = initialInfo(
            title = seedDisplay?.title ?: type.label,
            subtitle = seedDisplay?.subtitle,
            arguments = creationArguments,
        ).copy(
            hasUnsavedChanges = true,
            isPausable = false,
        ),
    )

    init {
        log(tag, INFO) { "SaverWorkspace initialized: $id with ${sourceUris.size} source(s)" }

        // Extract source info for all URIs on initialization
        scope.launch {
            val infos = sourceUris.mapNotNull { uri ->
                try {
                    contentUriHelper.extractInfo(uri)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to extract source info for $uri: ${e.asLog()}" }
                    null
                }
            }
            _sourceInfos.value = infos
            log(tag) { "Extracted info for ${infos.size}/${sourceUris.size} sources" }

            // Set filename from first file if single file mode
            if (infos.size == 1) {
                _filename.value = infos.first().displayName
            }
        }

        // Resolve caller package name to app label
        creationArguments.callerPackage?.let { pkgId ->
            scope.launch {
                try {
                    if (isUnknownCaller(pkgId)) {
                        log(tag) { "Skipping label resolution for unknown caller: $pkgId" }
                        return@launch
                    }
                    val label = pkgOps.getLabel(pkgId)
                    if (label != null) {
                        _callerLabel.value = label
                        log(tag) { "Resolved caller label: $pkgId -> $label" }
                    } else {
                        log(tag) { "No label found for $pkgId, keeping default" }
                    }
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to resolve caller label for $pkgId: ${e.asLog()}" }
                }
            }
        }

        // Combine all state flows into single state
        combine(
            _sourceInfos,
            _destination,
            _filename,
            _saveState,
            _callerLabel,
        ) { sourceInfos, destination, filename, saveState, callerLabel ->
            State(
                sourceInfos = sourceInfos,
                destination = destination,
                filename = filename,
                saveState = saveState,
                callerLabel = callerLabel,
                callerPackage = creationArguments.callerPackage?.takeIf { !isUnknownCaller(it) },
                createdAt = createdAt,
                callerWorkspaceId = creationArguments.callerWorkspaceId,
            )
        }
            .onEach { _state.updateBlocking { it } }
            .launchIn(scope)
    }

    override suspend fun createArguments(): SaverArguments = SaverArguments.Default(
        sourceUris = creationArguments.sourceUris,
        callerPackage = creationArguments.callerPackage,
        destinationPath = _destination.value,
        callerWorkspaceId = creationArguments.callerWorkspaceId,
        modalPresentation = creationArguments.modalPresentation,
        reportSavedPaths = creationArguments.reportSavedPaths,
    )

    override suspend fun release() {
        log(tag, INFO) { "Releasing SaverWorkspace: $id" }
        scope.cancel()
    }

    // Public methods for ViewModel

    fun setDestination(path: APath<*>) {
        log(tag) { "setDestination($path)" }
        _destination.value = path
    }

    fun updateFilename(name: String) {
        log(tag) { "updateFilename($name)" }
        _filename.value = name
    }

    fun refreshSourceAccessibility() {
        scope.launch {
            val currentInfos = _sourceInfos.value
            if (currentInfos.isEmpty()) return@launch

            val updatedInfos = currentInfos.map { info ->
                val isAccessible = contentUriHelper.checkAccessibility(info.uri)
                if (info.isAccessible != isAccessible) {
                    info.copy(isAccessible = isAccessible)
                } else {
                    info
                }
            }

            if (updatedInfos != currentInfos) {
                _sourceInfos.value = updatedInfos
                log(tag) { "Refreshed accessibility: ${updatedInfos.count { it.isAccessible }}/${updatedInfos.size} accessible" }
            }
        }
    }

    suspend fun save() {
        val destination = _destination.value
        require(destination != null) { "Destination must be set before saving" }

        val infos = _sourceInfos.value
        require(infos.isNotEmpty()) { "No source files to save" }

        // For single file mode, use custom filename if set
        val sources = if (infos.size == 1 && _filename.value.isNotBlank()) {
            listOf(
                SaveFilesOperation.Command.SourceFile(
                    uri = infos.first().uri,
                    filename = _filename.value,
                    size = infos.first().size,
                )
            )
        } else {
            infos.map { info ->
                SaveFilesOperation.Command.SourceFile(
                    uri = info.uri,
                    filename = info.displayName,
                    size = info.size,
                )
            }
        }

        // Idempotency guard: reject a concurrent save (e.g. rapid double-tap) so we never submit
        // two operations for the same workspace. Check-and-set the initial state under a lock.
        val accepted = saveMutex.withLock {
            if (_saveState.value !is SaveState.Idle) {
                false
            } else {
                _saveState.value = SaveState.Saving(
                    currentFile = 0,
                    totalFiles = sources.size,
                    currentFilename = sources.firstOrNull()?.filename ?: "",
                )
                true
            }
        }
        if (!accepted) {
            log(tag, WARN) { "save() ignored - a save is already in progress (${_saveState.value})" }
            return
        }

        log(tag, INFO) { "Starting save of ${sources.size} file(s) to $destination" }

        val operation = saveFilesOperationFactory.create(
            workspaceId = id,
            command = SaveFilesOperation.Command(
                sources = sources,
                targetDirectory = destination,
            ),
        )

        val operationId = operationsManager.submit(operation)
        _currentOperationId.value = operationId

        // Observe operation state
        operationsManager.operations
            .map { ops -> ops.find { it.id == operationId } }
            .filterNotNull()
            .flatMapLatest { it.state }
            .onEach { state ->
                when (state) {
                    is SaveFilesOperation.State.Active -> {
                        val progress = state.primaryProgress
                        _saveState.value = SaveState.Saving(
                            currentFile = progress.count.current.toInt(),
                            totalFiles = progress.count.max.toInt(),
                            currentFilename = "",
                        )
                    }
                    is SaveFilesOperation.State.Completed -> {
                        if (state.error != null) {
                            _saveState.value = SaveState.Error(state.error)
                        } else {
                            _saveState.value = SaveState.Success(state.report)
                        }
                    }
                    is Operation.State.Completed -> {
                        // Generic completion (e.g., cancelled via anonymous object from ManagedOperation)
                        log(tag, INFO) { "Operation completed generically (cancelled?), resetting state" }
                        resetSaveState()
                    }
                    else -> {}
                }
            }
            .launchIn(scope)
    }

    fun resetSaveState() {
        _saveState.value = SaveState.Idle
        _currentOperationId.value = null
    }

    fun resolveConflict(operationId: Operation.Id, resolution: PathActionIssue.Resolution) {
        log(tag, INFO) { "Resolving conflict for operation $operationId: $resolution" }
        scope.launch {
            issueHandler.resolveIssue(operationId, resolution)
        }
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<SaverArguments> {
        override fun create(id: Workspace.Id, arguments: SaverArguments): SaverWorkspace

        override val argumentsSerializer: KSerializer<SaverArguments> get() = serializer()

        override fun deriveDisplay(arguments: SaverArguments): WorkspaceDisplay? =
            deriveSaverDisplay(arguments)
    }

    companion object {
        private const val UNKNOWN_CALLER_LABEL = "?"
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.SAVER)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
