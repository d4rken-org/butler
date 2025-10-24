package eu.darken.butler.workspace.ui.manager.preview

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePage
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.preview.WorkspacePreviewRepo
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.flowOf
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
    ): File? = withContext(AndroidUiDispatcher.Main) {
        log(tag, INFO) { "Capturing preview for workspace ${workspaceId.shortTag} (${workspaceType})" }

        try {
            WorkspaceDesign(
                layout = WorkspaceDesign.Layout.SINGLE
            )

            val bitmap = useVirtualDisplay(context) { display ->
                captureComposable(
                    context = context,
                    size = DpSize(400.dp, 800.dp),
                    display = display
                ) {
                    LaunchedEffect(Unit) {
                        capture()
                    }

//                    Box(modifier = Modifier.fillMaxSize().background(Color.Red))

                    Box(modifier = Modifier.fillMaxSize()) {
//                        WorkspaceMapper(
//                            info = WorkspacePaneInfo(
//                                id = workspaceId,
//                                type = workspaceType,
//                            ),
//                            design = design,
//                        )

                        val mockState = ExplorerWorkspaceViewModel.State(
                            currentLocation = ExplorerLocation.Directory(
                                path = LocalPath.build("/storage/emulated/0"),
                                items = MockDataProvider.createAllFileTypes(),
                                info = ExplorerLocation.Directory.Info(
                                    fileCount = 15,
                                    directoryCount = 5,
                                    totalSize = 1024L * 1024L * 250L,
                                    volumeFreeSpace = 1024L * 1024L * 1024L * 50L,
                                    volumeTotalSpace = 1024L * 1024L * 1024L * 128L,
                                    isWritable = true,
                                ),
                                progress = null,
                            ),
                            breadcrumbs = listOf(
                                ExplorerBreadcrumb(
                                    label = R.string.explorer_navigation_home.toCaString(),
                                    target = ExplorerNavigation.Target.Home
                                ),
                                ExplorerBreadcrumb(
                                    label = R.string.explorer_navigation_device.toCaString(),
                                    target = ExplorerNavigation.Target.Device
                                ),
                                ExplorerBreadcrumb(
                                    label = "storage".toCaString(),
                                    target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage"))
                                ),
                                ExplorerBreadcrumb(
                                    label = "emulated".toCaString(),
                                    target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated"))
                                ),
                                ExplorerBreadcrumb(
                                    label = "0".toCaString(),
                                    target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0"))
                                )
                            ),
                            items = MockDataProvider.createAllFileTypes(),
                            availableActions = listOf(
                                ExplorerAction.Directory.Create(isEnabled = false),
                                ExplorerAction.Common.Sort(),
                                ExplorerAction.Common.Filter(isEnabled = false),
                            ),
                        )
                        ExplorerWorkspacePage(
                            workspaceId = Workspace.Id(),
                            mainStateSource = flowOf(mockState),
                            workspaceStateSource = flowOf(null),
                            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
                            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
                            vm = null,
                        )
                    }
                }
            }

            // Save to preview cache (switch to IO dispatcher for file operations)
            val savedFile = withContext(dispatcherProvider.IO) {
                previewRepo.savePreview(workspaceId, bitmap.asAndroidBitmap())
            }

            log(tag, INFO) {
                "Successfully saved preview for ${workspaceId.shortTag}: ${savedFile.length()} bytes"
            }

            savedFile
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to capture preview for ${workspaceId.shortTag}: ${e.asLog()}" }
            null
        } finally {

        }
    }
}
