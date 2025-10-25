package eu.darken.butler.workspace.ui.manager.preview

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.ViewModelStoreOwner
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.theming.MyAppTheme
import eu.darken.butler.common.theming.themeState
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspaceMapper
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import kotlinx.coroutines.flow.first
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
) {

    suspend fun captureWorkspace(
        workspaceId: Workspace.Id,
        workspaceType: Workspace.Type,
        size: DpSize,
        captureContext: Context,
        viewmodelStoreOwner: ViewModelStoreOwner? = null,
    ): Bitmap? = try {
        log(TAG, INFO) { "Capturing preview for workspace ${workspaceId.shortTag} (${workspaceType})" }

        val themeState = generalSettings.themeState.first()

        withContext(dispatcherProvider.Main) {
            composableBitmapRenderer.renderToBitmap(
                canvasSize = size,
                captureContext = captureContext,
                viewModelStoreOwner = viewmodelStoreOwner,
            ) {
                MyAppTheme(state = themeState) {
                        WorkspaceMapper(
                            info = WorkspacePaneInfo(
                                id = workspaceId,
                                type = workspaceType,
                            ),
                            design = WorkspaceDesign(
                                layout = WorkspaceDesign.Layout.SINGLE
                            ),
                        )
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
