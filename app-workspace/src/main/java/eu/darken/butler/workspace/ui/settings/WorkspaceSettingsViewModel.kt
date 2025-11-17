package eu.darken.butler.workspace.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.session.WorkspaceSessionManager
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class WorkspaceSettingsViewModel
@Inject
constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceSettings: WorkspaceSettings,
    private val sessionManager: WorkspaceSessionManager,
) : ViewModel4(dispatcherProvider, logTag("Workspace", "Settings", "Screen", "VM"), navCtrl) {

    val state = combine(
        workspaceSettings.swipeGesturesEnabled.flow,
        workspaceSettings.onDemandWorkspaceCreation.flow,
        workspaceSettings.livePreview.flow,
        workspaceSettings.layoutModePortrait.flow,
        workspaceSettings.layoutModeLandscape.flow,
        workspaceSettings.sessionRestoreEnabled.flow,
        workspaceSettings.restoreSearchResults.flow,
        workspaceSettings.maxWorkspacesToRestore.flow,
    ) { values ->
        State(
            swipeGesturesEnabled = values[0] as Boolean,
            onDemandWorkspaceCreation = values[1] as Boolean,
            livePreview = values[2] as Boolean,
            layoutModePortrait = values[3] as WorkspacePanelMode,
            layoutModeLandscape = values[4] as WorkspacePanelMode,
            sessionRestoreEnabled = values[5] as Boolean,
            restoreSearchResults = values[6] as Boolean,
            maxWorkspacesToRestore = values[7] as Int,
        )
    }.asStateFlow()

    fun toggleSwipeGestures() = launch {
        val current = workspaceSettings.swipeGesturesEnabled.value()
        workspaceSettings.swipeGesturesEnabled.value(!current)
    }

    fun toggleOnDemandWorkspaceCreation() = launch {
        val current = workspaceSettings.onDemandWorkspaceCreation.value()
        workspaceSettings.onDemandWorkspaceCreation.value(!current)
    }

    fun toggleLivePreview() = launch {
        val current = workspaceSettings.livePreview.value()
        workspaceSettings.livePreview.value(!current)
    }

    fun setLayoutModePortrait(mode: WorkspacePanelMode) = launch {
        workspaceSettings.layoutModePortrait.value(mode)
    }

    fun setLayoutModeLandscape(mode: WorkspacePanelMode) = launch {
        workspaceSettings.layoutModeLandscape.value(mode)
    }

    fun toggleSessionRestore() = launch {
        val current = workspaceSettings.sessionRestoreEnabled.value()
        workspaceSettings.sessionRestoreEnabled.value(!current)
    }

    fun toggleRestoreSearchResults() = launch {
        val current = workspaceSettings.restoreSearchResults.value()
        workspaceSettings.restoreSearchResults.value(!current)
    }

    fun clearSession() = launch {
        sessionManager.clearSession()
    }

    fun setMaxWorkspacesToRestore(max: Int) = launch {
        workspaceSettings.maxWorkspacesToRestore.value(max)
    }

    data class State(
        val swipeGesturesEnabled: Boolean,
        val onDemandWorkspaceCreation: Boolean,
        val livePreview: Boolean,
        val layoutModePortrait: WorkspacePanelMode,
        val layoutModeLandscape: WorkspacePanelMode,
        val sessionRestoreEnabled: Boolean,
        val restoreSearchResults: Boolean,
        val maxWorkspacesToRestore: Int,
    )
}