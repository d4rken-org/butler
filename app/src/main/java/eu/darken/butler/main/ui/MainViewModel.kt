package eu.darken.butler.main.ui

import android.content.Context
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.storage.DocumentUriResolver
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.PasteFileReader
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.external.ExternalOpenFailedException
import eu.darken.butler.main.core.external.ExternalOpenOption
import eu.darken.butler.main.core.external.ExternalOpenRouter
import eu.darken.butler.main.core.external.ExternalOpenState
import eu.darken.butler.main.core.external.SourceRef
import eu.darken.butler.main.core.external.computeExternalOpenOptions
import eu.darken.butler.main.core.themeState
import eu.darken.butler.main.core.themeStateBlocking
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json


@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    dispatcherProvider: DispatcherProvider,
    private val upgradeRepo: UpgradeRepo,
    private val generalSettings: GeneralSettings,
    private val workspaceRemote: WorkspaceRemote,
    private val json: Json,
    private val documentUriResolver: DocumentUriResolver,
    private val operationFocusRequest: OperationFocusRequest,
    private val contentUriHelper: ContentUriHelper,
    private val externalOpenRouter: ExternalOpenRouter,
    private val pasteFileReader: PasteFileReader,
) : ViewModel4(dispatcherProvider, logTag("Main", "Screen", "VM")) {

    val themeState = generalSettings.themeState.stateIn(
        vmScope,
        SharingStarted.Eagerly,
        generalSettings.themeStateBlocking,
    )

    val state = combine(
        generalSettings.isOnboardingCompleted.flow,
        generalSettings.isDisplayCutoutAvoided.flow,
    ) { onBoardingComplete, avoidDisplayCutout ->
        State(
            startScreen = when {
                !onBoardingComplete -> State.StartScreen.ONBOARDING
                else -> State.StartScreen.HOME
            },
            avoidDisplayCutout = avoidDisplayCutout,
        )
    }
        .onEach { log(VERBOSE) { "New state: $it" } }
        .asStateFlow()

    fun checkUpgrades() = launch {
        log(tag) { "checkUpgrades()" }
        upgradeRepo.refresh()
    }

    /**
     * Notification tap → focus the owning workspace; if an [operationId] is supplied (a "tap to
     * resolve" conflict notification), also publish a focus request so the workspace's ViewModel
     * surfaces the matching conflict sheet.
     */
    fun focusOperationWorkspace(workspaceId: Workspace.Id, operationId: Operation.Id?) = launch {
        log(tag) { "focusOperationWorkspace($workspaceId, $operationId)" }
        workspaceRemote.emitEvent(WorkspaceEvent.SelectionRequested(workspaceId))
        if (operationId != null) operationFocusRequest.request(workspaceId, operationId)
    }

    data class State(
        val startScreen: StartScreen = StartScreen.ONBOARDING,
        val avoidDisplayCutout: Boolean = false,
    ) {
        enum class StartScreen {
            ONBOARDING,
            HOME,
            ;
        }
    }

    fun openDirectoryFromShortcut(serializedPath: String) = launch {
        log(tag) { "openDirectoryFromShortcut($serializedPath)" }
        try {
            val path = json.decodeFromString(PolymorphicSerializer(APath::class), serializedPath)
            workspaceRemote.createAndFocus(
                type = Workspace.Type.EXPLORER,
                arguments = ExplorerArguments.Default(startPath = path)
            )
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to open directory from shortcut: ${e.asLog()}" }
        }
    }

    fun createNewExplorerWorkspace() = launch {
        log(tag) { "createNewExplorerWorkspace()" }
        try {
            workspaceRemote.createAndFocus(
                type = Workspace.Type.EXPLORER,
                arguments = ExplorerArguments.Default()
            )
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create new Explorer workspace: ${e.message}" }
        }
    }

    fun createSaverWorkspace(
        sourceUris: List<Uri>,
        callerPackage: String?,
    ) = launch {
        log(tag) { "createSaverWorkspace(uris=${sourceUris.size})" }
        try {
            workspaceRemote.createAndFocus(
                type = Workspace.Type.SAVER,
                arguments = SaverArguments.Default(
                    sourceUris = sourceUris.map { it.toString() },
                    callerPackage = callerPackage?.toPkgId(),
                )
            )
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create Saver workspace: ${e.asLog()}" }
        }
    }

    fun createEditorWorkspaceWithText(
        text: String,
        subject: String?,
    ) = launch {
        log(tag) { "createEditorWorkspaceWithText(textLength=${text.length}, subject=$subject)" }
        try {
            workspaceRemote.createAndFocus(
                type = Workspace.Type.EDITOR,
                arguments = EditorArguments.Default(
                    filePath = null,
                    initialContent = text,
                    suggestedTitle = subject,
                )
            )
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create Editor workspace with text: ${e.asLog()}" }
        }
    }

    private val _externalOpen = MutableStateFlow<ExternalOpenState?>(null)

    /** A file another app handed to Butler, waiting for the user to pick what should happen with it. */
    val externalOpen: StateFlow<ExternalOpenState?>
        get() = _externalOpen

    private var externalOpenJob: Job? = null

    /**
     * An "Open with" arrival: collects the metadata needed to decide what Butler can offer for the
     * file. The newest arrival wins, an older one that is still gathering metadata is dropped.
     */
    fun onExternalFile(uri: Uri, intentType: String?, callerPackage: String?) {
        log(tag) { "onExternalFile($uri, $intentType, $callerPackage)" }
        externalOpenJob?.cancel()
        externalOpenJob = vmScope.launch {
            val ref = externalOpenRouter.sanitize(uri)
            if (ref == null) {
                log(tag, WARN) { "Refusing to open $uri" }
                return@launch
            }

            val displayName: String
            val rawSize: Long?
            val resolverType: String?
            when (ref) {
                is SourceRef.Content -> {
                    val info = contentUriHelper.extractInfo(ref.uri)
                    displayName = info.displayName
                    rawSize = info.size
                    resolverType = info.mimeType
                }

                is SourceRef.Local -> {
                    displayName = ref.path.name
                    rawSize = ref.path.file.length()
                    resolverType = null
                }
            }

            val sizeBytes = rawSize?.takeIf { it >= 0 }
            val mime = externalOpenRouter.resolveMime(
                intentType = intentType,
                resolverType = resolverType,
                displayName = displayName,
            )

            _externalOpen.value = ExternalOpenState(
                ref = ref,
                originalUri = uri,
                displayName = displayName,
                sizeBytes = sizeBytes,
                mime = mime,
                callerPackage = callerPackage,
                options = computeExternalOpenOptions(mime, sizeBytes),
            ).also { log(tag) { "External open state: $it" } }
        }
    }

    fun onExternalOpenAction(option: ExternalOpenOption) {
        log(tag) { "onExternalOpenAction($option)" }
        // Claim the arrival before anything suspends, so a double tap can't act on it twice.
        val state = _externalOpen.getAndUpdate { null }
        if (state == null) {
            log(tag, WARN) { "No pending external file for $option" }
            return
        }

        launch {
            try {
                when (option) {
                    ExternalOpenOption.VIEW -> openExternalInViewer(state)
                    ExternalOpenOption.EDIT_AS_TEXT -> openExternalInEditor(state)
                    ExternalOpenOption.SAVE_AS -> openExternalInSaver(state)
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to handle $option for ${state.displayName}: ${e.asLog()}" }
                errorEvents.emit(ExternalOpenFailedException(state.displayName, e))
            }
        }
    }

    fun onExternalOpenDismiss() {
        log(tag) { "onExternalOpenDismiss()" }
        _externalOpen.value = null
    }

    private suspend fun openExternalInViewer(state: ExternalOpenState) {
        val path = externalOpenRouter.resolveForView(state.ref, state.mime, state.displayName)
        if (path == null) {
            log(tag, WARN) { "Could not resolve ${state.originalUri} for viewing" }
            errorEvents.emit(ExternalOpenFailedException(state.displayName))
            return
        }
        workspaceRemote.createAndFocus(
            type = Workspace.Type.VIEWER,
            arguments = ViewerArguments.Default(filePath = path),
        )
    }

    private suspend fun openExternalInEditor(state: ExternalOpenState) {
        val text = pasteFileReader.read {
            when (val ref = state.ref) {
                is SourceRef.Content -> context.contentResolver.openInputStream(state.originalUri)
                is SourceRef.Local -> ref.path.file.inputStream()
            }
        }.getOrElse { error ->
            log(tag, WARN) { "Could not read ${state.originalUri} as text: ${error.asLog()}" }
            errorEvents.emit(ExternalOpenFailedException(state.displayName, error))
            return
        }
        workspaceRemote.createAndFocus(
            type = Workspace.Type.EDITOR,
            arguments = EditorArguments.Default(
                filePath = null,
                initialContent = text,
                suggestedTitle = state.displayName,
            ),
        )
    }

    private suspend fun openExternalInSaver(state: ExternalOpenState) {
        workspaceRemote.createAndFocus(
            type = Workspace.Type.SAVER,
            arguments = SaverArguments.Default(
                sourceUris = listOf(state.originalUri.toString()),
                callerPackage = state.callerPackage?.toPkgId(),
            ),
        )
    }

    /**
     * Opens an Explorer workspace from an external storage document URI.
     * Handles URIs like: content://com.android.externalstorage.documents/root/FD76-F8FE
     */
    fun openFromDocumentUri(uri: Uri) = launch {
        log(tag) { "openFromDocumentUri($uri)" }
        try {
            val path = documentUriResolver.resolve(uri)
            if (path != null) {
                log(tag) { "Resolved document URI to path: $path" }
                workspaceRemote.createAndFocus(
                    type = Workspace.Type.EXPLORER,
                    arguments = ExplorerArguments.Default(startPath = path)
                )
            } else {
                log(tag, WARN) { "Could not resolve document URI to path: $uri" }
                workspaceRemote.createAndFocus(
                    type = Workspace.Type.EXPLORER,
                    arguments = ExplorerArguments.Default()
                )
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to open from document URI: ${e.asLog()}" }
        }
    }
}
