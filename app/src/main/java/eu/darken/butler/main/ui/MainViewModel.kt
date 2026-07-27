package eu.darken.butler.main.ui

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.storage.DocumentUriResolver
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.themeState
import eu.darken.butler.main.core.themeStateBlocking
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json


@HiltViewModel
class MainViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val upgradeRepo: UpgradeRepo,
    private val generalSettings: GeneralSettings,
    private val workspaceRemote: WorkspaceRemote,
    private val json: Json,
    private val documentUriResolver: DocumentUriResolver,
    private val operationFocusRequest: OperationFocusRequest,
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
        val avoidDisplayCutout: Boolean = true,
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
