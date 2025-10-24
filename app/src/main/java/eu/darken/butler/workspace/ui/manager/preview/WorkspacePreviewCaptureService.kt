package eu.darken.butler.workspace.ui.manager.preview

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.AndroidUiDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.preview.WorkspacePreviewRepo
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspaceMapper
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for capturing workspace UI to preview images.
 *
 * This service renders workspace pages offscreen using ComposeView and captures
 * them to PNG files stored in the app cache directory. The files are managed by
 * [WorkspacePreviewRepo] and loaded by Coil for display in TabManager preview cards.
 *
 * Captures are triggered:
 * 1. After workspace creation (delayed 2-3 seconds for initialization)
 * 2. When workspace becomes inactive (user switches away)
 *
 * All captures are performed offscreen to ensure consistency regardless of
 * visible UI state.
 */
@Singleton
class WorkspacePreviewCaptureService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val previewRepo: WorkspacePreviewRepo,
    private val composableBitmapRenderer: ComposableBitmapRenderer,
) {
    private val tag = logTag("Workspace", "PreviewCapture")

    /**
     * Captures a workspace page and saves it to the preview cache.
     *
     * This method:
     * 1. Creates an offscreen ComposeView
     * 2. Renders the workspace page using [WorkspacePageComposer]
     * 3. Measures and lays out the view at preview dimensions
     * 4. Captures to bitmap in RGB_565 format
     * 5. Saves to PNG file via [WorkspacePreviewRepo]
     * 6. Cleans up temporary bitmap
     *
     * The workspace page will inject its existing ViewModel via Hilt (keyed by workspace ID),
     * so the captured preview reflects the real workspace state.
     *
     * @param workspaceId The ID of the workspace to capture
     * @param workspaceType The type of workspace (Explorer, Searcher, etc.)
     * @return The saved preview file, or null if capture fails
     */
    suspend fun captureWorkspace(
        workspaceId: Workspace.Id,
        workspaceType: Workspace.Type,
    ): File? = try {
        log(tag, INFO) { "Capturing preview for workspace ${workspaceId.shortTag} (${workspaceType})" }
        val bitmap = withContext(AndroidUiDispatcher.Main) {
            composableBitmapRenderer.renderToBitmap(Size(400f, 800f)) {
                Box(modifier = Modifier.fillMaxSize()) {
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
        if (bitmap == null) throw IllegalStateException("Workspace preview bitmap is null")

        // Save to preview cache (switch to IO dispatcher for file operations)
        val savedFile = withContext(dispatcherProvider.IO) {
            previewRepo.savePreview(workspaceId, bitmap)
        }

        log(tag, INFO) { "Saved preview for ${workspaceId.shortTag}: ${savedFile.length()} bytes" }

        savedFile
    } catch (e: Exception) {
        log(tag, WARN) { "Failed to capture preview for ${workspaceId.shortTag}: ${e.asLog()}" }
        null
    }

}
