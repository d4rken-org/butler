package eu.darken.butler.workspace.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage
import javax.inject.Inject
import kotlin.time.Duration

@HiltViewModel
class WorkspaceSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val workspaceSettings: WorkspaceSettings,
    private val sessionStorage: eu.darken.butler.workspace.core.session.WorkspaceSessionStorage,
) : ViewModel4(dispatcherProvider, logTag("Workspace", "Settings", "Screen", "VM")) {

    val state = combine(
        workspaceSettings.swipeGesturesEnabled.flow,
        workspaceSettings.onDemandWorkspaceCreation.flow,
        workspaceSettings.livePreview.flow,
        workspaceSettings.layoutModePortrait.flow,
        workspaceSettings.layoutModeLandscape.flow,
        workspaceSettings.sessionRestoreEnabled.flow,
        workspaceSettings.autoPauseEnabled.flow,
        workspaceSettings.autoPauseIdleTimeout.flow,
        sessionStorage.getWorkspaceCount(WorkspaceSessionStorage.DEFAULT_SESSION_ID),
        sessionStorage.getDatabaseSizeBytes(WorkspaceSessionStorage.DEFAULT_SESSION_ID),
    ) { swipeGesturesEnabled, onDemandWorkspaceCreation, livePreview, layoutModePortrait, layoutModeLandscape, paneClickToFocus, sessionRestoreEnabled, autoPauseEnabled, autoPauseIdleTimeout, sessionWorkspaceCount, sessionDatabaseSizeBytes ->
        State(
            swipeGesturesEnabled = swipeGesturesEnabled,
            onDemandWorkspaceCreation = onDemandWorkspaceCreation,
            livePreview = livePreview,
            layoutModePortrait = layoutModePortrait,
            layoutModeLandscape = layoutModeLandscape,
            paneClickToFocus = paneClickToFocus,
            sessionRestoreEnabled = sessionRestoreEnabled,
            autoPauseEnabled = autoPauseEnabled,
            autoPauseIdleTimeout = WorkspaceSettings.clampIdleTimeout(autoPauseIdleTimeout),
            sessionWorkspaceCount = sessionWorkspaceCount,
            sessionDatabaseSizeBytes = sessionDatabaseSizeBytes,
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

    fun togglePaneClickToFocus() = launch {
        val current = workspaceSettings.paneClickToFocus.value()
        workspaceSettings.paneClickToFocus.value(!current)
    }

    fun toggleSessionRestore() = launch {
        val current = workspaceSettings.sessionRestoreEnabled.value()
        workspaceSettings.sessionRestoreEnabled.value(!current)
        if (current) {
            sessionStorage.dao.clearAllSessionData(WorkspaceSessionStorage.DEFAULT_SESSION_ID)
        }
    }

    fun toggleAutoPause() = launch {
        val current = workspaceSettings.autoPauseEnabled.value()
        workspaceSettings.autoPauseEnabled.value(!current)
    }

    fun setAutoPauseIdleTimeout(timeout: Duration) = launch {
        workspaceSettings.autoPauseIdleTimeout.value(WorkspaceSettings.clampIdleTimeout(timeout))
    }

    data class State(
        val swipeGesturesEnabled: Boolean,
        val onDemandWorkspaceCreation: Boolean,
        val livePreview: Boolean,
        val layoutModePortrait: WorkspacePanelMode,
        val layoutModeLandscape: WorkspacePanelMode,
        val paneClickToFocus: Boolean,
        val sessionRestoreEnabled: Boolean,
        val autoPauseEnabled: Boolean = true,
        val autoPauseIdleTimeout: Duration = WorkspaceSettings.AUTO_PAUSE_IDLE_TIMEOUT_DEFAULT,
        val sessionWorkspaceCount: Int = 0,
        val sessionDatabaseSizeBytes: Long = 0L,
    )
}