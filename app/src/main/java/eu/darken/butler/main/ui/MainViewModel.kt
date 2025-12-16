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
import eu.darken.butler.common.theming.themeState
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.arguments.EditorArguments
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.saver.core.arguments.SaverArguments
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject


@HiltViewModel
class MainViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val upgradeRepo: UpgradeRepo,
    private val generalSettings: GeneralSettings,
    private val workspaceRemote: WorkspaceRemote,
    private val json: Json,
) : ViewModel4(dispatcherProvider, logTag("Main", "Screen", "VM")) {

    val themeState = generalSettings.themeState.asStateFlow()

    val state = generalSettings.isOnboardingCompleted.flow
        .combine(flowOf(Unit)) { onBoardingComplete, _ ->
            State(
                startScreen = when {
                    !onBoardingComplete -> State.StartScreen.ONBOARDING
                    else -> State.StartScreen.HOME
                },
            )
        }
        .onEach { log(VERBOSE) { "New state: $it" } }
        .asStateFlow()

    fun checkUpgrades() = launch {
        log(tag) { "checkUpgrades()" }
        upgradeRepo.refresh()
    }

    data class State(
        val startScreen: StartScreen = StartScreen.ONBOARDING,
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
}
