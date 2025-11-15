package eu.darken.butler.workspace.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class WorkspaceSettingsViewModel
@Inject
constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceSettings: WorkspaceSettings,
) : ViewModel4(dispatcherProvider, logTag("Workspace", "Settings", "Screen", "VM"), navCtrl) {

    val state = combine(
        workspaceSettings.swipeGesturesEnabled.flow,
        workspaceSettings.onDemandWorkspaceCreation.flow,
        workspaceSettings.livePreview.flow,
        workspaceSettings.layoutModePortrait.flow,
        workspaceSettings.layoutModeLandscape.flow,
    ) { swipeGesturesEnabled, onDemandWorkspaceCreation, livePreview, layoutModePortrait, layoutModeLandscape ->
        State(
            swipeGesturesEnabled = swipeGesturesEnabled,
            onDemandWorkspaceCreation = onDemandWorkspaceCreation,
            livePreview = livePreview,
            layoutModePortrait = layoutModePortrait,
            layoutModeLandscape = layoutModeLandscape,
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

    data class State(
        val swipeGesturesEnabled: Boolean,
        val onDemandWorkspaceCreation: Boolean,
        val livePreview: Boolean,
        val layoutModePortrait: WorkspacePanelMode,
        val layoutModeLandscape: WorkspacePanelMode,
    )
}