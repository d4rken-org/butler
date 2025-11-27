package eu.darken.butler.saver.core

import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.arguments.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
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
    private val saveOperation: SaveOperation,
    private val json: Json,
) : Workspace<SaverArguments> {

    private val tag = logTag("Saver", "Workspace", id.shortTag)

    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    override val type: Workspace.Type = Workspace.Type.SAVER

    private val creationArguments: SaverArguments.Default = arguments as SaverArguments.Default

    val sourceUri: Uri = Uri.parse(creationArguments.sourceUri)

    // State flows for UI
    private val _sourceInfo = MutableStateFlow<ContentUriHelper.SourceInfo?>(null)
    val sourceInfo: Flow<ContentUriHelper.SourceInfo?> = _sourceInfo

    private val _destination = MutableStateFlow<APath<*>?>(null)
    val destination: Flow<APath<*>?> = _destination

    private val _filename = MutableStateFlow(creationArguments.customFilename ?: "")
    val filename: Flow<String> = _filename

    private val _saveState = MutableStateFlow<SaveOperation.State>(SaveOperation.State.Idle)
    val saveState: Flow<SaveOperation.State> = _saveState

    private val _state = DynamicStateFlow<State>(parentScope = scope) { State() }
    val state: Flow<State> = _state.flow

    data class State(
        val sourceInfo: ContentUriHelper.SourceInfo? = null,
        val destination: APath<*>? = null,
        val filename: String = "",
        val saveState: SaveOperation.State = SaveOperation.State.Idle,
        val callerPackage: String? = null,
    )

    override val info: Flow<Workspace.Info> = combine(
        _filename,
        _saveState,
    ) { filename, saveState ->
        Workspace.Info(
            id = id,
            type = type,
            title = when {
                Bugs.isDebug -> "Saver ${id.shortTag}".toCaString()
                else -> R.string.saver_workspace_title.toCaString()
            },
            subtitle = filename.toCaString(),
            operationCount = if (saveState is SaveOperation.State.Saving) 1 else 0,
            attentionCount = if (saveState is SaveOperation.State.Error) 1 else 0,
            callerWorkspaceId = null,
        )
    }

    init {
        log(tag, INFO) { "SaverWorkspace initialized: $id with source: $sourceUri" }

        // Extract source info on initialization
        scope.launch {
            try {
                val info = contentUriHelper.extractInfo(sourceUri)
                _sourceInfo.value = info
                // Set filename from extracted info if not already set by customFilename
                if (_filename.value.isEmpty()) {
                    _filename.value = info.displayName
                }
                log(tag) { "Source info extracted: $info" }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to extract source info: ${e.asLog()}" }
            }
        }

        // Restore destination if previously selected
        creationArguments.destinationPath?.let { pathJson ->
            try {
                // Note: This requires polymorphic serialization of APath
                // For now we'll handle restoration in a simpler way
                log(tag) { "Destination path restoration not yet implemented: $pathJson" }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to restore destination: ${e.asLog()}" }
            }
        }

        // Combine all state flows into single state
        combine(
            _sourceInfo,
            _destination,
            _filename,
            _saveState,
        ) { sourceInfo, destination, filename, saveState ->
            State(
                sourceInfo = sourceInfo,
                destination = destination,
                filename = filename,
                saveState = saveState,
                callerPackage = creationArguments.callerPackage,
            )
        }
            .onEach { _state.updateBlocking { it } }
            .launchIn(scope)
    }

    override suspend fun createArguments(): SaverArguments = SaverArguments.Default(
        sourceUri = creationArguments.sourceUri,
        mimeType = creationArguments.mimeType,
        callerPackage = creationArguments.callerPackage,
        destinationPath = null, // TODO: Serialize destination path
        customFilename = _filename.value.takeIf { it.isNotEmpty() },
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
            val currentInfo = _sourceInfo.value ?: return@launch
            val isAccessible = contentUriHelper.checkAccessibility(sourceUri)
            if (currentInfo.isAccessible != isAccessible) {
                _sourceInfo.value = currentInfo.copy(isAccessible = isAccessible)
            }
        }
    }

    fun save(): Flow<SaveOperation.State> {
        val destination = _destination.value
        val filename = _filename.value

        require(destination != null) { "Destination must be set before saving" }
        require(filename.isNotBlank()) { "Filename must not be blank" }

        log(tag, INFO) { "Starting save: $filename -> $destination" }

        return saveOperation.execute(
            sourceUri = sourceUri,
            targetDirectory = destination,
            filename = filename,
            totalBytes = _sourceInfo.value?.size,
        ).onEach { state ->
            _saveState.value = state
        }
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
}
