package eu.darken.butler.saver.core

import android.net.Uri
import androidx.core.net.toUri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.arguments.SaverArguments
import eu.darken.butler.saver.core.operations.SaveFilesOperation
import eu.darken.butler.saver.core.operations.SaveFilesReport
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

class SaverWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: SaverArguments,
    dispatcherProvider: DispatcherProvider,
    private val contentUriHelper: ContentUriHelper,
    private val operationsManager: OperationsManager,
    private val saveFilesOperationFactory: SaveFilesOperation.Factory,
    private val pkgOps: PkgOps,
    private val json: Json,
) : Workspace<SaverArguments> {

    private val tag = logTag("Saver", "Workspace", id.shortTag)

    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    override val type: Workspace.Type = Workspace.Type.SAVER

    private val creationArguments: SaverArguments.Default = arguments as SaverArguments.Default

    val sourceUris: List<Uri> = creationArguments.sourceUris.map { it.toUri() }

    val isBatchMode: Boolean get() = sourceUris.size > 1

    // State flows for UI
    private val _sourceInfos = MutableStateFlow<List<ContentUriHelper.SourceInfo>>(emptyList())
    val sourceInfos: Flow<List<ContentUriHelper.SourceInfo>> = _sourceInfos

    private val _destination = MutableStateFlow<APath<*>?>(creationArguments.destinationPath)
    val destination: Flow<APath<*>?> = _destination

    // Only used for single file mode
    private val _filename = MutableStateFlow("")
    val filename: Flow<String> = _filename

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: Flow<SaveState> = _saveState

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
    ) {
        val isBatchMode: Boolean get() = sourceInfos.size > 1
        val fileCount: Int get() = sourceInfos.size
        val totalSize: Long? get() = sourceInfos.mapNotNull { it.size }.takeIf { it.size == sourceInfos.size }?.sum()
        val hasInaccessibleFiles: Boolean get() = sourceInfos.any { !it.isAccessible }
    }

    override val info: Flow<Workspace.Info> = combine(
        _sourceInfos,
        _filename,
        _saveState,
    ) { sourceInfos, filename, saveState ->
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
            title = when {
                Bugs.isDebug -> "Saver ${id.shortTag}".toCaString()
                sourceInfos.size > 1 -> caString { cx ->
                    cx.getQuantityString2(
                        R.plurals.saver_workspace_title_count,
                        sourceInfos.size,
                        sourceInfos.size,
                    )
                }
                else -> R.string.saver_workspace_title.toCaString()
            },
            subtitle = when {
                sourceInfos.size > 1 -> sourceInfos.firstOrNull()?.displayName?.let { "$it, ..." }?.toCaString()
                    ?: "".toCaString()
                else -> filename.toCaString()
            },
            operationCount = operationCount,
            attentionCount = attentionCount,
            callerWorkspaceId = null,
        )
    }

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
            )
        }
            .onEach { _state.updateBlocking { it } }
            .launchIn(scope)
    }

    override suspend fun createArguments(): SaverArguments = SaverArguments.Default(
        sourceUris = creationArguments.sourceUris,
        callerPackage = creationArguments.callerPackage,
        destinationPath = _destination.value,
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

        log(tag, INFO) { "Starting save of ${sources.size} file(s) to $destination" }

        _saveState.value = SaveState.Saving(
            currentFile = 0,
            totalFiles = sources.size,
            currentFilename = sources.firstOrNull()?.filename ?: "",
        )

        val operation = saveFilesOperationFactory.create(
            workspaceId = id,
            command = SaveFilesOperation.Command(
                sources = sources,
                targetDirectory = destination,
            ),
        )

        val operationId = operationsManager.submit(operation)

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
                    else -> {}
                }
            }
            .launchIn(scope)
    }

    fun resetSaveState() {
        _saveState.value = SaveState.Idle
    }

    private fun isUnknownCaller(pkgId: Pkg.Id): Boolean {
        val name = pkgId.name.lowercase()
        return name == "shell" || name == "com.android.shell" || name.isEmpty()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<SaverArguments> {
        override fun create(id: Workspace.Id, arguments: SaverArguments): SaverWorkspace

        override fun serialize(json: Json, arguments: SaverArguments): JsonElement {
            return json.encodeToJsonElement<SaverArguments>(arguments)
        }

        override fun deserialize(json: Json, element: JsonElement): SaverArguments {
            return json.decodeFromJsonElement<SaverArguments>(element)
        }
    }

    companion object {
        private const val UNKNOWN_CALLER_LABEL = "?"
    }
}
