package eu.darken.butler.viewer.ui.viewer

import android.widget.Toast
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ApkInstallState
import eu.darken.butler.viewer.core.VersionComparison
import androidx.core.net.toUri
import eu.darken.butler.common.files.errors.PathGoneError
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerExternalChange
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.error.ErrorCard
import eu.darken.butler.workspace.ui.states.PathGoneBody
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
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
import kotlin.time.Duration.Companion.seconds

private val VIEWER_EXTERNAL_CHANGE_POLL_INTERVAL = 15.seconds

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
    LaunchedEffect(vm) {
        vm.toastEvents.collect { message ->
            Toast.makeText(context, message.get(context), Toast.LENGTH_SHORT).show()
        }
    }

    // External-change polling only runs while this page is resumed; background tabs stay quiet. The
    // probe comes first and the wait after, so a tab returning to the foreground checks right away.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.checkExternalChange()
                delay(VIEWER_EXTERNAL_CHANGE_POLL_INTERVAL)
            }
        }
    }

    val state by vm.state.collectAsState(initial = null)
    val callerWorkspaceId by vm.callerWorkspaceId.collectAsState(initial = null)

    state?.let {
        ViewerWorkspacePage(
            workspaceId = id,
            design = design,
            state = it,
            callerWorkspaceId = callerWorkspaceId,
            onAction = { action ->
                when (action) {
                    is ViewerActionBarItem.PreviousFile -> vm.showPreviousFile()
                    is ViewerActionBarItem.NextFile -> vm.showNextFile()
                    ViewerActionBarItem.OpenWith -> vm.openWith()
                    ViewerActionBarItem.Share -> vm.share()
                    ViewerActionBarItem.Copy -> vm.copyToClipboard()
                    ViewerActionBarItem.Cut -> vm.cutToClipboard()
                    is ViewerActionBarItem.OpenLocation -> vm.openLocation()
                    is ViewerActionBarItem.Delete -> vm.requestDelete()
                    ViewerActionBarItem.SaveCopy -> vm.saveCopy()
                    ViewerActionBarItem.BrowseArchive -> vm.browseArchive()
                    ViewerActionBarItem.OpenInEditor -> vm.openInEditor()
                    ViewerActionBarItem.Install -> vm.install()
                }
            },
            onOpenWith = { vm.openWith() },
            onSaveCopy = { vm.saveCopy() },
            onBrowseArchive = { vm.browseArchive() },
            onOpenInEditor = { vm.openInEditor() },
            onRetry = { vm.retry() },
            onShareError = { error -> vm.shareError(error) },
            onShowIcon = { vm.showIconPreview() },
            onSaveIcon = { vm.saveIcon() },
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
    /** Test and preview seam for the tap-hidden chrome, mirroring [ApkFileContent]'s expand seam. */
    initiallyChromeVisible: Boolean = true,
    onAction: (ViewerActionBarItem) -> Unit = {},
    onOpenWith: () -> Unit = {},
    onSaveCopy: () -> Unit = {},
    onBrowseArchive: () -> Unit = {},
    onOpenInEditor: () -> Unit = {},
    onRetry: () -> Unit = {},
    onShareError: (Throwable) -> Unit = {},
    onShowIcon: () -> Unit = {},
    onSaveIcon: () -> Unit = {},
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

    // Tap the content and every floating card leaves; tap again and they come back.
    var chromeVisible by rememberSaveable { mutableStateOf(initiallyChromeVisible) }

    // Zooming in is the user asking for the picture, not for its metadata. Zooming back out to fit
    // restores the chrome; a tap in between still overrides it either way.
    //
    // Driven off the first *settled* zoom, not the first composition: telephoto reports an
    // unspecified transformation until it has laid out, so a restored page passes through a
    // spurious "not zoomed" before its real zoom arrives. Taking the baseline at the first
    // specified transformation is what keeps a restored [chromeVisible] from being overwritten.
    val zoomSpecified by remember(zoomableState) {
        derivedStateOf { zoomableState.contentTransformation.isSpecified }
    }
    var zoomBaselineTaken by remember { mutableStateOf(false) }
    LaunchedEffect(zoomSpecified, zoomedIn) {
        if (!zoomSpecified) return@LaunchedEffect
        if (!zoomBaselineTaken) {
            zoomBaselineTaken = true
            return@LaunchedEffect
        }
        chromeVisible = !zoomedIn
    }

    // The notice would be silent exactly when it matters: the chrome is gone whenever the user has
    // tapped the picture away, and zooming in hides it on its own.
    val externalChange = (state as? ViewerWorkspaceViewModel.State.Ready)?.externalChange
    LaunchedEffect(externalChange) {
        if (externalChange != null) chromeVisible = true
    }

    // Remembered, not rebuilt per composition: this ends up as the `pointerInput` key of the tap
    // detectors below, and a key that changes every frame restarts the detector - cancelling any
    // gesture already in flight, so taps land only when the page happens to be idle.
    val toggleChrome: () -> Unit = remember(topBarStackState, bottomBarStackState) {
        {
            // A scroll-hidden bar is still `visible = true`, so a plain toggle after a scroll-away
            // would hide it a second time and change nothing the user can see. The first tap reveals.
            val scrollHidden = topBarStackState.collapseTargets.values.any { it > 0f } ||
                bottomBarStackState.collapseTargets.values.any { it > 0f }
            if (scrollHidden) {
                topBarStackState.resetScrollCollapse()
                bottomBarStackState.resetScrollCollapse()
                chromeVisible = true
            } else {
                chromeVisible = !chromeVisible
            }
        }
    }

    // Same reasoning: a fresh list per composition would re-key the nestedScroll modifiers.
    val barScrollConnections = remember(topBarStackState, bottomBarStackState) {
        listOf(topBarStackState.nestedScrollConnection, bottomBarStackState.nestedScrollConnection)
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
                // Neither a spinner nor an error card is a surface the user can tap, and retry and
                // back both live in the chrome - it must not stay hidden from an earlier tap. A PDF
                // page without a bitmap is the same situation: it is either still rendering or has
                // failed, and both put an untappable surface on screen.
                val pdfPageNotShown = state.content is ViewerContent.PdfPreview &&
                    state.pdfPage?.bitmap == null
                val chromeShown = chromeVisible ||
                    state.content is ViewerContent.Failed ||
                    state.content is ViewerContent.Loading ||
                    pdfPageNotShown

                ViewerContentArea(
                    state = state,
                    zoomableState = zoomableState,
                    contentPadding = contentPadding,
                    barScrollConnections = barScrollConnections,
                    onOpenWith = onOpenWith,
                    onSaveCopy = onSaveCopy,
                    onBrowseArchive = onBrowseArchive,
                    onOpenInEditor = onOpenInEditor,
                    onRetry = onRetry,
                    onShareError = onShareError,
                    onShowIcon = onShowIcon,
                    onSaveIcon = onSaveIcon,
                    onToggleChrome = toggleChrome,
                )

                FloatingBarStack(
                    state = topBarStackState,
                    position = BarPosition.TOP,
                    modifier = Modifier.align(Alignment.TopCenter),
                    bars = {
                        FloatingBar(
                            key = ViewerBarKeys.TOOLBAR,
                            visible = chromeShown,
                            scrollBehavior = BarScrollBehavior.CollapseOnScroll,
                            animation = BarAnimation.Slide(),
                        ) {
                            ViewerToolbarCard(
                                workspaceId = workspaceId,
                                design = design,
                                fileName = state.source.displayName,
                                // The name is already the title above it, so repeating it here as
                                // the tail of the full path told the user nothing. Null for streamed
                                // content, which lives nowhere Butler could name.
                                folderPath = state.source.folderPath,
                                isCollapsed = isToolbarCollapsed || collapsedFraction > 0.5f,
                                onBackClick = if (isModal) {
                                    { onPageAction(ViewerPageAction.Close) }
                                } else null,
                            )
                        }

                        FloatingBar(
                            key = ViewerBarKeys.EXTERNAL_CHANGE,
                            visible = chromeShown && externalChange != null,
                            scrollBehavior = BarScrollBehavior.HideOnScroll,
                            animation = BarAnimation.Slide(),
                            // A bar scrolled away before the file changed has to come back for it.
                            revealOn = externalChange,
                        ) {
                            externalChange?.let {
                                ViewerExternalChangeBanner(change = it, onRefresh = onRetry)
                            }
                        }
                    },
                )

                FloatingBarStack(
                    state = bottomBarStackState,
                    position = BarPosition.BOTTOM,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    bars = {
                        // Bars in a BOTTOM stack are declared top-to-bottom, so the page bar leads
                        // and the action bar comes last, sitting at the screen edge the way every
                        // other workspace has it.
                        val pdfContent = state.content as? ViewerContent.PdfPreview
                        FloatingBar(
                            key = ViewerBarKeys.PDF_HINT,
                            visible = chromeShown && pdfContent != null,
                            scrollBehavior = BarScrollBehavior.HideOnScroll,
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

                        // Metadata read before the failure describes a file that is no longer
                        // there, so next to the error it would read as current.
                        val fileInfo = state.fileInfo?.takeIf { state.content !is ViewerContent.Failed }
                        FloatingBar(
                            key = ViewerBarKeys.FILEINFO,
                            visible = chromeShown && fileInfo != null,
                            scrollBehavior = BarScrollBehavior.HideOnScroll,
                            animation = BarAnimation.Slide(),
                        ) {
                            // Expanded from the start when a share carried a message: it is the
                            // one entry the user has not seen before, and it sits last in the card.
                            fileInfo?.let {
                                FileInfoCard(
                                    fileInfo = it,
                                    initiallyExpanded = it.sharedCaption != null,
                                )
                            }
                        }

                        FloatingBar(
                            key = ViewerBarKeys.ACTIONS,
                            // The bar wraps its content, so an empty action list would leave an
                            // empty pill floating over the image rather than nothing at all.
                            visible = chromeShown && state.actions.any { it.isVisible },
                            scrollBehavior = BarScrollBehavior.HideOnScroll,
                            animation = BarAnimation.Slide(),
                        ) {
                            WorkspaceActionBar(
                                actions = state.actions,
                                onActionClick = onAction,
                            )
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
    barScrollConnections: List<NestedScrollConnection> = emptyList(),
    onOpenWith: () -> Unit,
    onSaveCopy: () -> Unit = {},
    onBrowseArchive: () -> Unit = {},
    onOpenInEditor: () -> Unit = {},
    onRetry: () -> Unit,
    onShareError: (Throwable) -> Unit,
    onShowIcon: () -> Unit = {},
    onSaveIcon: () -> Unit = {},
    onToggleChrome: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val content = state.content) {
            ViewerContent.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )

            is ViewerContent.Image -> state.imageSource?.let { source ->
                ZoomableFileImage(
                    imageSource = source,
                    fileName = state.source.displayName,
                    state = zoomableState,
                    onClick = onToggleChrome,
                )
            }

            // The only branch that scrolls behind the floating bars, so it is also the only one
            // that has to inset for them - and the only one that can drive their scroll behaviour.
            is ViewerContent.Apk -> ApkFileContent(
                apkInfo = content.apkInfo,
                installState = content.installState,
                contentPadding = contentPadding,
                onShowIcon = onShowIcon,
                onSaveIcon = onSaveIcon,
                barScrollConnections = barScrollConnections,
                onToggleChrome = onToggleChrome,
            )

            is ViewerContent.AppBundle -> AppBundleFileContent(
                format = content.format,
                apkInfo = content.apkInfo,
                installState = content.installState,
                splitCount = content.splitCount,
                hasObb = content.hasObb,
                needsElevationForObb = content.needsElevationForObb,
                contentPadding = contentPadding,
                barScrollConnections = barScrollConnections,
                onToggleChrome = onToggleChrome,
            )

            is ViewerContent.PdfPreview -> PdfPreviewContent(
                pdfPage = state.pdfPage,
                pageCount = content.pageCount,
                fileName = state.source.displayName,
                zoomableState = zoomableState,
                onClick = onToggleChrome,
                onRetry = onRetry,
            )

            is ViewerContent.Text -> TextFileContent(
                preview = state.textPreview?.preview,
                failed = state.textPreview?.failed == true,
                // Asked of the action bar rather than re-derived: it already knows when the Editor
                // has nothing to open, and two answers to that would drift apart.
                editorAvailable = state.actions.contains(ViewerActionBarItem.OpenInEditor),
                contentPadding = contentPadding,
                barScrollConnections = barScrollConnections,
                onOpenInEditor = onOpenInEditor,
                onRetry = onRetry,
                onToggleChrome = onToggleChrome,
            )

            is ViewerContent.Archive -> ArchivePlaceholder(
                format = content.format,
                access = content.access,
                // Same gate the action bar's entry goes through: a browse button for a container
                // that is no longer there is a dead end.
                isGone = state.externalChange == ViewerExternalChange.Gone,
                onBrowse = onBrowseArchive,
                onSaveCopy = onSaveCopy,
                onToggleChrome = onToggleChrome,
            )

            is ViewerContent.Unsupported -> UnsupportedFilePlaceholder(
                mimeType = content.mime.rawType,
                isStreamed = state.source is ViewerSource.Streamed,
                onOpenWith = onOpenWith,
                onSaveCopy = onSaveCopy,
                onToggleChrome = onToggleChrome,
            )

            // A file that is simply gone is not a fault to report or retry, so it gets the shared
            // "path is gone" treatment rather than an error card with a dead retry button.
            is ViewerContent.Failed -> if (content.error is PathGoneError) {
                PathGoneBody(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    error = content.error,
                )
            } else {
                ErrorCard(
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
}

private val previewPath = LocalPath.build("/storage/emulated/0/DCIM/Camera/IMG_20240817_183042.jpg")
private val previewSource = ViewerSource.Stored(previewPath)

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
            source = previewSource,
            imageSource = null,
        ),
    )
}

/** Opened from an Explorer listing: the bar leads with the two steps through that listing. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerWorkspacePageSteppablePreview() {
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.Image(MimeInfo("image/jpeg")),
            fileInfo = previewFileInfo,
            source = previewSource,
            imageSource = null,
            // Last file of its folder, so stepping forward is offered but not available.
            neighbours = ViewerNeighbours(
                current = previewPath,
                previous = LocalPath.build("/storage/emulated/0/DCIM/Camera/IMG_20240817_183041.jpg"),
                next = null,
            ),
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
            source = previewSource,
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
            source = ViewerSource.Stored(LocalPath.build("/storage/emulated/0/Download/manual.pdf")),
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
            source = ViewerSource.Stored(LocalPath.build("/storage/emulated/0/Download/manual.pdf")),
            imageSource = null,
        ),
    )
}

/** An archive: no renderer, but a way in, and the action bar leads with it. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerWorkspacePageArchivePreview() {
    val source = ViewerSource.Stored(LocalPath.build("/storage/emulated/0/Download/backup.zip"))
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.Archive(
                mime = MimeInfo("application/zip"),
                format = ArchiveFormat.ZIP,
                access = ViewerContent.Archive.Access.BROWSABLE,
            ),
            fileInfo = ViewerFileInfo(size = 12_004_311L, modifiedAt = Clock.System.now()),
            source = source,
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
            source = ViewerSource.Stored(LocalPath.build("/storage/emulated/0/Download/butler.apk")),
            imageSource = null,
        ),
    )
}

/** Streamed content: no folder block, and "Save a copy" is the only action. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerWorkspacePageStreamedPreview() {
    val streamed = ViewerSource.Streamed(
        uri = "content://com.example.files/document/42".toUri(),
        displayName = "holiday.jpg",
        mime = MimeInfo("image/jpeg"),
        sizeBytes = 2_411_200L,
        arrivalId = "preview",
    )
    ViewerWorkspacePage(
        workspaceId = Workspace.Id(),
        state = ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.Image(MimeInfo("image/jpeg")),
            fileInfo = ViewerFileInfo(size = 2_411_200L),
            source = streamed,
            imageSource = null,
            actions = viewerActions(streamed, trashEnabled = false),
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
            source = previewSource,
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
