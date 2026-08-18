package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import eu.darken.butler.viewer.core.ApkInstallState
import eu.darken.butler.viewer.core.VersionComparison
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.error.ErrorCard
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Page-level intents the viewer hands back to its host. */
sealed interface ViewerPageAction {
    /** Leave a drill-down viewer and return to the workspace that opened the file. */
    data object Close : ViewerPageAction
}

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
    val callerWorkspaceId by vm.callerWorkspaceId.collectAsState(initial = null)

    state?.let {
        ViewerWorkspacePage(
            workspaceId = id,
            design = design,
            state = it,
            callerWorkspaceId = callerWorkspaceId,
            onOpenWith = { vm.openWith() },
            onRetry = { vm.retry() },
            onShareError = { error -> vm.shareError(error) },
            onPdfPreviousPage = { vm.previousPdfPage() },
            onPdfNextPage = { vm.nextPdfPage() },
            onPageAction = { action ->
                when (action) {
                    ViewerPageAction.Close -> vm.close()
                }
            },
        )
    }
}

@Composable
fun ViewerWorkspacePage(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: ViewerWorkspaceViewModel.State,
    callerWorkspaceId: Workspace.Id? = null,
    onOpenWith: () -> Unit = {},
    onRetry: () -> Unit = {},
    onShareError: (Throwable) -> Unit = {},
    onPdfPreviousPage: () -> Unit = {},
    onPdfNextPage: () -> Unit = {},
    onPageAction: (ViewerPageAction) -> Unit = {},
) {
    // A drill-down viewer is an overlay in its opener's pane, so back belongs to it in every phase -
    // including while the image loads and after a failure, where there is nothing else to dismiss.
    val isModal = callerWorkspaceId != null
    WorkspaceBackHandler(enabled = isModal) { onPageAction(ViewerPageAction.Close) }

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

    val contentPadding = rememberFloatingBarContentPadding(
        topStackState = topBarStackState,
        bottomStackState = bottomBarStackState,
        start = WorkspacePaddings.ContentHorizontal,
        end = WorkspacePaddings.ContentHorizontal,
    )

    // Hoisted: the toolbar is a sibling of the content area, so it cannot read the zoom itself.
    val zoomableState = rememberZoomableState()
    // derivedStateOf keeps pan frames out of the page: contentTransformation carries scale and
    // offset in one object, so a direct read would recompose both floating stacks on every frame.
    val zoomedIn by remember(zoomableState) {
        derivedStateOf {
            val transformation = zoomableState.contentTransformation
            isZoomedIn(
                transformationSpecified = transformation.isSpecified,
                userZoom = if (transformation.isSpecified) transformation.scaleMetadata.userZoom else 1f,
            )
        }
    }

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
                val isToolbarCollapsed = shouldCollapseToolbar(state.content, zoomedIn)

                ViewerContentArea(
                    state = state,
                    zoomableState = zoomableState,
                    contentPadding = contentPadding,
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
                                fullPath = state.path.path,
                                isCollapsed = isToolbarCollapsed,
                                onBackClick = if (isModal) {
                                    { onPageAction(ViewerPageAction.Close) }
                                } else null,
                            )
                        }
                    },
                )

                FloatingBarStack(
                    state = bottomBarStackState,
                    position = BarPosition.BOTTOM,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    bars = {
                        // Bars in a BOTTOM stack are declared top-to-bottom, so the page bar and the
                        // action bar come first to sit above the metadata card.
                        val pdfContent = state.content as? ViewerContent.PdfPreview
                        FloatingBar(
                            key = ViewerBarKeys.PDF_HINT,
                            visible = pdfContent != null,
                            animation = BarAnimation.Slide(),
                            // The bar wraps its content, so its slot has to span the pane for the
                            // stack's BottomCenter alignment to center it.
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            pdfContent?.let {
                                PdfPageBar(
                                    pageIndex = state.pdfPage?.index ?: 0,
                                    pageCount = it.pageCount,
                                    isRendering = state.pdfPage?.let { page ->
                                        page.bitmap == null && !page.failed
                                    } ?: true,
                                    onPreviousPage = onPdfPreviousPage,
                                    onNextPage = onPdfNextPage,
                                )
                            }
                        }

                        FloatingBar(
                            key = ViewerBarKeys.ACTIONS,
                            visible = true,
                            animation = BarAnimation.Slide(),
                        ) {
                            WorkspaceActionBar(
                                actions = listOf(ViewerActionBarItem.OpenWith),
                                onActionClick = { onOpenWith() },
                            )
                        }

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

/** Telephoto reports userZoom 1.0 at rest; `isSpecified` is false until the first layout pass. */
internal const val ZOOM_COLLAPSE_THRESHOLD = 1.01f

internal fun isZoomedIn(transformationSpecified: Boolean, userZoom: Float): Boolean =
    transformationSpecified && userZoom > ZOOM_COLLAPSE_THRESHOLD

/**
 * Telephoto keeps its transformation when the image composable leaves, so a decode failure after a
 * zoom would otherwise strand the toolbar collapsed with no gesture surface left to expand it.
 *
 * A rendered PDF page is the same kind of surface, so it collapses the toolbar the same way.
 */
internal fun shouldCollapseToolbar(content: ViewerContent, isZoomedIn: Boolean): Boolean =
    (content is ViewerContent.Image || content is ViewerContent.PdfPreview) && isZoomedIn

@Composable
private fun ViewerContentArea(
    modifier: Modifier = Modifier,
    state: ViewerWorkspaceViewModel.State.Ready,
    zoomableState: ZoomableState,
    contentPadding: PaddingValues,
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
                ZoomableFileImage(
                    imageSource = source,
                    fileName = state.path.name,
                    state = zoomableState,
                )
            }

            // The only branch that scrolls behind the floating bars, so it is also the only one
            // that has to inset for them.
            is ViewerContent.Apk -> ApkFileContent(
                apkInfo = content.apkInfo,
                installState = content.installState,
                contentPadding = contentPadding,
            )

            is ViewerContent.PdfPreview -> PdfPreviewContent(
                pdfPage = state.pdfPage,
                pageCount = content.pageCount,
                fileName = state.path.name,
                zoomableState = zoomableState,
                onRetry = onRetry,
            )

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
private fun ViewerWorkspacePageModalPreview() {
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.Image(MimeInfo("image/jpeg")),
            fileInfo = previewFileInfo,
            path = previewPath,
            imageSource = null,
        ),
        callerWorkspaceId = Workspace.Id(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerWorkspacePagePdfPreviewPreview() {
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.PdfPreview(MimeInfo("application/pdf"), pageCount = 3),
            fileInfo = ViewerFileInfo(size = 128_004L, modifiedAt = Clock.System.now()),
            path = LocalPath.build("/storage/emulated/0/Download/manual.pdf"),
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
private fun ViewerWorkspacePageApkPreview() {
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.Apk(
                mime = MimeInfo(MimeInfo.MIME_APK),
                apkInfo = previewApkInfo,
                installState = ApkInstallState.Installed(
                    versionName = "1.3.0",
                    versionCode = 130,
                    comparison = VersionComparison.APK_NEWER,
                ),
            ),
            fileInfo = ViewerFileInfo(size = 24_112_004L, modifiedAt = Clock.System.now()),
            path = LocalPath.build("/storage/emulated/0/Download/butler.apk"),
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
