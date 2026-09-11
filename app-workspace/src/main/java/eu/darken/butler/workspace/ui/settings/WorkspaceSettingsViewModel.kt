package eu.darken.butler.workspace.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import eu.darken.butler.workspace.ui.template.availableTemplates
import eu.darken.butler.workspace.ui.template.newTabCandidates
import eu.darken.butler.workspace.ui.template.newTabTemplate
import javax.inject.Inject
import kotlin.time.Duration

@HiltViewModel
class WorkspaceSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val workspaceSettings: WorkspaceSettings,
    private val sessionStorage: eu.darken.butler.workspace.core.session.WorkspaceSessionStorage,
    workspaceTemplates: Set<@JvmSuppressWildcards WorkspaceTemplate>,
) : ViewModel4(dispatcherProvider, logTag("Workspace", "Settings", "Screen", "VM")) {

    val state = combine(
        workspaceSettings.swipeGesturesEnabled.flow,
        workspaceSettings.onDemandWorkspaceCreation.flow,
        workspaceSettings.defaultNewTabType.flow,
        workspaceTemplates.availableTemplates(),
        workspaceSettings.livePreview.flow,
        workspaceSettings.layoutModePortrait.flow,
        workspaceSettings.layoutModeLandscape.flow,
        workspaceSettings.paneClickToFocus.flow,
        workspaceSettings.sessionRestoreEnabled.flow,
        workspaceSettings.autoPauseEnabled.flow,
        workspaceSettings.autoPauseIdleTimeout.flow,
        workspaceSettings.undoCloseEnabled.flow,
        sessionStorage.getWorkspaceCount(WorkspaceSessionStorage.DEFAULT_SESSION_ID),
        sessionStorage.getDatabaseSizeBytes(WorkspaceSessionStorage.DEFAULT_SESSION_ID),
    ) { swipeGesturesEnabled, onDemandWorkspaceCreation, defaultNewTabType, templates, livePreview, layoutModePortrait, layoutModeLandscape, paneClickToFocus, sessionRestoreEnabled, autoPauseEnabled, autoPauseIdleTimeout, undoCloseEnabled, sessionWorkspaceCount, sessionDatabaseSizeBytes ->
        State(
            swipeGesturesEnabled = swipeGesturesEnabled,
            onDemandWorkspaceCreation = onDemandWorkspaceCreation,
            // The effective type, so the row never names one the gesture would not honour. The
            // stored value stays untouched, a type that becomes available again is restored.
            defaultNewTabType = templates.newTabTemplate(defaultNewTabType)?.type ?: Workspace.Type.TEMPLATES,
            newTabCandidates = templates.newTabCandidates(),
            livePreview = livePreview,
            layoutModePortrait = layoutModePortrait,
            layoutModeLandscape = layoutModeLandscape,
            paneClickToFocus = paneClickToFocus,
            sessionRestoreEnabled = sessionRestoreEnabled,
            autoPauseEnabled = autoPauseEnabled,
            autoPauseIdleTimeout = WorkspaceSettings.clampIdleTimeout(autoPauseIdleTimeout),
            undoCloseEnabled = undoCloseEnabled,
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

    fun setDefaultNewTabType(type: Workspace.Type) = launch {
        workspaceSettings.defaultNewTabType.value(type)
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

    fun toggleUndoClose() = launch {
        val current = workspaceSettings.undoCloseEnabled.value()
        workspaceSettings.undoCloseEnabled.value(!current)
    }

    data class State(
        val swipeGesturesEnabled: Boolean,
        val onDemandWorkspaceCreation: Boolean,
        val defaultNewTabType: Workspace.Type = Workspace.Type.TEMPLATES,
        val newTabCandidates: List<WorkspaceTemplate> = emptyList(),
        val livePreview: Boolean,
        val layoutModePortrait: WorkspacePanelMode,
        val layoutModeLandscape: WorkspacePanelMode,
        val paneClickToFocus: Boolean,
        val sessionRestoreEnabled: Boolean,
        val autoPauseEnabled: Boolean = true,
        val autoPauseIdleTimeout: Duration = WorkspaceSettings.AUTO_PAUSE_IDLE_TIMEOUT_DEFAULT,
        val undoCloseEnabled: Boolean = true,
        val sessionWorkspaceCount: Int = 0,
        val sessionDatabaseSizeBytes: Long = 0L,
    )
}