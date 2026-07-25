package eu.darken.butler.workspace.ui.manager.preview

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.ViewModelStoreOwner
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.theming.ButlerTheme
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.themeStateBlocking
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspacePauseGate
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspaceMapper
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for capturing workspace UI to preview images.
 *
 * All captures are performed offscreen to ensure consistency regardless of visible UI state.
 */
@Singleton
class WorkspacePreviewCaptureService @Inject constructor(
    private val composableBitmapRenderer: ComposableBitmapRenderer,
    private val dispatcherProvider: DispatcherProvider,
    private val generalSettings: GeneralSettings,
    private val workspacePauseGate: WorkspacePauseGate,
    private val pageHosts: Map<Workspace.Type, @JvmSuppressWildcards WorkspacePageHostEntry>,
) {

    /**
     * Callers must not pass a paused workspace: the capture composes the type's page host by
     * synthesizing [Workspace.LifecycleState.Ready], which has no instance to bind to while paused
     * (and resuming it here would defeat the pause).
     *
     * The whole capture runs under [WorkspacePauseGate], so a pause of this workspace can neither
     * start nor finish while we compose it. We wait for a pause in flight rather than bail out: a
     * skipped capture would leave a stale thumbnail behind until something else invalidates it,
     * while waiting only delays a preview by one pause.
     */
    suspend fun captureWorkspace(
        workspaceId: Workspace.Id,
        workspaceType: Workspace.Type,
        size: DpSize,
        captureContext: Context,
        viewmodelStoreOwner: ViewModelStoreOwner? = null,
    ): Bitmap? = try {
        log(TAG, INFO) { "Capturing preview for workspace ${workspaceId.shortTag} (${workspaceType})" }

        workspacePauseGate.withLease(workspaceId) {
            val themeState = generalSettings.themeStateBlocking

            withContext(dispatcherProvider.Main) {
                composableBitmapRenderer.renderToBitmap(
                    canvasSize = size,
                    captureContext = captureContext,
                    viewModelStoreOwner = viewmodelStoreOwner,
                ) {
                    // Offscreen capture renders in a detached composition that doesn't inherit the
                    // app's locals, so the page host map must be re-provided here or every preview
                    // falls back to "no page host registered" error content.
                    // Only the page host's Content is rendered (via WorkspaceMapper) — a preview
                    // thumbnail must never compose a workspace's dialogs or sheets.
                    // Disable focus during preview capture to prevent keyboard from showing
                    CompositionLocalProvider(
                        LocalWorkspaceFocused provides false,
                        LocalWorkspacePageHosts provides pageHosts,
                    ) {
                        ButlerTheme(state = themeState) {
                            WorkspaceMapper(
                                info = WorkspacePaneInfo(
                                    id = workspaceId,
                                    type = workspaceType,
                                    lifecycleState = Workspace.LifecycleState.Ready,
                                    // Only Ready is composed here, which draws the page, not a title
                                    title = workspaceType.label,
                                ),
                                design = WorkspaceDesign(
                                    layout = WorkspaceDesign.Layout.SINGLE
                                ),
                                onShareError = { /* No-op for preview */ },
                                onCloseWorkspace = { /* No-op for preview */ },
                                onResumeWorkspace = { /* No-op for preview */ },
                            )
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        log(TAG, WARN) { "Failed to capture preview for ${workspaceId.shortTag}: ${e.asLog()}" }
        null
    }

    companion object {
        private val TAG = logTag("Workspace", "PreviewCapture")
    }
}
