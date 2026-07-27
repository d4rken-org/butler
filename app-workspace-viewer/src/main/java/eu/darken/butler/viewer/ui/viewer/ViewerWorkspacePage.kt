package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.error.ErrorCard
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Composable
fun ViewerWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: ViewerWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: ViewerWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.shareIntentEvent.collect { intent -> context.startActivity(intent) }
    }

    val state by vm.state.collectAsState(initial = null)

    state?.let {
        ViewerWorkspacePage(
            workspaceId = id,
            design = design,
            state = it,
            onOpenWith = { vm.openWith() },
            onRetry = { vm.retry() },
            onShareError = { error -> vm.shareError(error) },
        )
    }
}

@Composable
fun ViewerWorkspacePage(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: ViewerWorkspaceViewModel.State,
    onOpenWith: () -> Unit = {},
    onRetry: () -> Unit = {},
    onShareError: (Throwable) -> Unit = {},
) {
    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
        estimatedContentPadding = 96.dp,
    )
    val bottomBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.BOTTOM,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        design = design,
        estimatedContentPadding = 120.dp,
    )

    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            ViewerWorkspaceViewModel.State.Initializing -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )

            is ViewerWorkspaceViewModel.State.Error -> ErrorCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                title = stringResource(R.string.viewer_error_title),
                error = state.error,
                onShareError = { onShareError(state.error) },
                onRetry = onRetry,
            )

            is ViewerWorkspaceViewModel.State.Ready -> {
                ViewerContentArea(
                    state = state,
                    onOpenWith = onOpenWith,
                    onRetry = onRetry,
                    onShareError = onShareError,
                )

                FloatingBarStack(
                    state = topBarStackState,
                    position = BarPosition.TOP,
                    modifier = Modifier.align(Alignment.TopCenter),
                    bars = {
                        FloatingBar(
                            key = ViewerBarKeys.TOOLBAR,
                            visible = true,
                            animation = BarAnimation.Slide(),
                        ) {
                            ViewerToolbarCard(
                                workspaceId = workspaceId,
                                design = design,
                                fileName = state.path.name,
                                parentPath = state.path.parent?.path,
                                onOpenWith = onOpenWith,
                            )
                        }
                    },
                )

                FloatingBarStack(
                    state = bottomBarStackState,
                    position = BarPosition.BOTTOM,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    bars = {
                        // Metadata read before the failure describes a file that is no longer
                        // there, so next to the error it would read as current.
                        val fileInfo = state.fileInfo?.takeIf { state.content !is ViewerContent.Failed }
                        FloatingBar(
                            key = ViewerBarKeys.FILEINFO,
                            visible = fileInfo != null,
                            animation = BarAnimation.Slide(),
                        ) {
                            fileInfo?.let { FileInfoCard(fileInfo = it) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ViewerContentArea(
    modifier: Modifier = Modifier,
    state: ViewerWorkspaceViewModel.State.Ready,
    onOpenWith: () -> Unit,
    onRetry: () -> Unit,
    onShareError: (Throwable) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val content = state.content) {
            ViewerContent.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )

            is ViewerContent.Image -> state.imageSource?.let { source ->
                ZoomableFileImage(imageSource = source, fileName = state.path.name)
            }

            is ViewerContent.Unsupported -> UnsupportedFilePlaceholder(
                mimeType = content.mime.rawType,
                onOpenWith = onOpenWith,
            )

            is ViewerContent.Failed -> ErrorCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                title = stringResource(R.string.viewer_error_title),
                error = content.error,
                onShareError = { onShareError(content.error) },
                onRetry = onRetry,
            )
        }
    }
}

private val previewPath = LocalPath.build("/storage/emulated/0/DCIM/Camera/IMG_20240817_183042.jpg")

private val previewFileInfo = ViewerFileInfo(
    size = 4_812_331L,
    modifiedAt = Clock.System.now() - 2.days,
    imageInfo = ViewerFileInfo.ImageInfo(format = "image/jpeg", width = 4032, height = 3024),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerWorkspacePageImagePreview() {
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.Image(MimeInfo("image/jpeg")),
            fileInfo = previewFileInfo,
            path = previewPath,
            imageSource = null,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerWorkspacePageUnsupportedPreview() {
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.Unsupported(MimeInfo("application/pdf")),
            fileInfo = ViewerFileInfo(size = 128_004L, modifiedAt = Clock.System.now()),
            path = LocalPath.build("/storage/emulated/0/Download/manual.pdf"),
            imageSource = null,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerWorkspacePageFailedPreview() {
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.Failed(IllegalStateException("Decoder gave up on a truncated JPEG")),
            fileInfo = previewFileInfo,
            path = previewPath,
            imageSource = null,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerWorkspacePageLoadingPreview() {
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Initializing,
    )
}
