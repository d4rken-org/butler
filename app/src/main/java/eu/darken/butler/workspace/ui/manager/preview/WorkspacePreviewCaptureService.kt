package eu.darken.butler.workspace.ui.manager.preview

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.ViewModelStoreOwner
import eu.darken.butler.common.compose.tour.LocalGuidedTourController
import eu.darken.butler.common.compose.tour.NoOpGuidedTourAccess
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.theming.ButlerRootSurface
import eu.darken.butler.common.theming.ButlerTheme
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.themeStateBlocking
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspacePauseGate
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.LocalWorkspaceTitles
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.tabLabel
import eu.darken.butler.workspace.ui.floatingbar.LocalWorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.scroll.LocalWorkspaceScrollPositions
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
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
    private val workspaceRepo: WorkspaceRepo,
    private val pageHosts: Map<Workspace.Type, @JvmSuppressWildcards WorkspacePageHostEntry>,
) {

    /**
     * Callers must not pass a paused workspace: the capture composes the type's page host by
     * synthesizing [Workspace.LifecycleState.Ready], which has no instance to bind to while paused
     * (and resuming it here would defeat the pause). The precondition is enforced defensively below
     * and a violation yields null, so callers still need their own fallback.
     *
     * The whole capture runs under [WorkspacePauseGate], keyed on this workspace's ownership root
     * (pausing acts on a whole unit, so the root is the only key that covers every participant), and
     * a pause of this unit can therefore neither start nor finish while we compose it. We wait for a
     * pause in flight rather than bail out: a
     * skipped capture would leave a stale thumbnail behind until something else invalidates it,
     * while waiting only delays a preview by one pause. Waiting means the caller's "is it live?"
     * check can have gone stale by the time we get the lease - a manual pause sticks instead of
     * resuming - so the workspace is re-read inside the lease before anything is composed.
     */
    suspend fun captureWorkspace(
        workspaceId: Workspace.Id,
        workspaceType: Workspace.Type,
        size: DpSize,
        captureContext: Context,
        viewmodelStoreOwner: ViewModelStoreOwner? = null,
    ): Bitmap? = try {
        log(TAG, INFO) { "Capturing preview for workspace ${workspaceId.shortTag} (${workspaceType})" }

        workspacePauseGate.withLease(workspaceRepo.peekOwnershipRoot(workspaceId)) {
            val currentInfo = workspaceRepo.peek(workspaceId)?.info?.value
            val skipReason = when {
                currentInfo == null -> "it is gone from the repo"
                currentInfo.isPaused -> "it was paused while we waited for the lease"
                else -> null
            }
            if (skipReason != null) {
                log(TAG, INFO) { "Skipping capture for ${workspaceId.shortTag}: $skipReason" }
                return@withLease null
            }

            val themeState = generalSettings.themeStateBlocking
            val workspaceTitles = workspaceRepo.peekInfos()
                .associate { it.id to it.tabLabel.get(captureContext) }

            withContext(dispatcherProvider.Main) {
                composableBitmapRenderer.renderToBitmap(
                    canvasSize = size,
                    captureContext = captureContext,
                    viewModelStoreOwner = viewmodelStoreOwner,
                ) {
                    // Only the page host's Content is rendered (via WorkspaceMapper) — a preview
                    // thumbnail must never compose a workspace's dialogs or sheets.
                    WorkspacePreviewCompositionLocals(
                        pageHosts = pageHosts,
                        workspaceTitles = workspaceTitles,
                    ) {
                        ButlerTheme(state = themeState) {
                            ButlerRootSurface {
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
        }
    } catch (e: Exception) {
        log(TAG, WARN) { "Failed to capture preview for ${workspaceId.shortTag}: ${e.asLog()}" }
        null
    }

    companion object {
        private val TAG = logTag("Workspace", "PreviewCapture")
    }
}

/**
 * The composition locals an offscreen capture has to establish itself.
 *
 * A capture renders in a detached composition that inherits none of the app's locals, and it
 * composes real pages: without the page host map every preview falls back to "no page host
 * registered" error content, and reading a local that has no default (the guided-tour controller)
 * throws outright — which the service's catch-all would silently turn into a null thumbnail.
 * Focus is off so no page pops the keyboard, and the view-state registries are deliberately fresh
 * detached ones so a capture cannot read or clobber the live scroll and bar-collapse state. The
 * workspace titles are the live ones, so a captured clipboard bar names its origin the same way the
 * on-screen one does.
 *
 * Extracted from [WorkspacePreviewCaptureService] so this set can be asserted on: the renderer
 * itself needs a VirtualDisplay and real bitmaps, neither of which exist in a unit test.
 */
@Composable
internal fun WorkspacePreviewCompositionLocals(
    pageHosts: Map<Workspace.Type, @JvmSuppressWildcards WorkspacePageHostEntry>,
    workspaceTitles: Map<Workspace.Id, String>,
    content: @Composable () -> Unit,
) {
    val previewScrollPositions = remember { WorkspaceScrollPositions() }
    val previewBarCollapse = remember { WorkspaceBarCollapseStates() }
    CompositionLocalProvider(
        LocalWorkspaceFocused provides false,
        LocalWorkspacePageHosts provides pageHosts,
        LocalWorkspaceScrollPositions provides previewScrollPositions,
        LocalWorkspaceBarCollapseStates provides previewBarCollapse,
        LocalGuidedTourController provides NoOpGuidedTourAccess,
        LocalWorkspaceTitles provides workspaceTitles,
        content = content,
    )
}
