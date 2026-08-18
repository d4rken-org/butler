package eu.darken.butler.viewer.ui.viewer

import android.graphics.Bitmap
import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.viewer.R
import kotlinx.coroutines.flow.Flow
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import eu.darken.butler.common.R as CommonR

/**
 * Wraps an already-rendered page bitmap. Routing it through telephoto instead of a plain Image keeps
 * its content-location math correct for a letterboxed page.
 */
internal class BitmapZoomableImageSource(
    private val bitmap: Bitmap,
) : ZoomableImageSource {

    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult =
        ZoomableImageSource.ResolveResult(
            delegate = ZoomableImageSource.PainterDelegate(BitmapPainter(bitmap.asImageBitmap())),
        )
}

/** One page of a PDF, zoomable like an image. A [pdfPage] without a bitmap is still rendering. */
@Composable
fun PdfPreviewContent(
    modifier: Modifier = Modifier,
    pdfPage: ViewerWorkspaceViewModel.PdfPage?,
    pageCount: Int,
    fileName: String,
    zoomableState: ZoomableState = rememberZoomableState(),
    onClick: (() -> Unit)? = null,
    onRetry: () -> Unit = {},
) {
    // Without this a page left zoomed in would hand its transform to the next one, which lands the
    // user somewhere in the middle of a page they have not seen yet.
    LaunchedEffect(pdfPage?.index) {
        zoomableState.resetZoom(animationSpec = SnapSpec())
    }

    when {
        pdfPage?.failed == true -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.TwoTone.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = stringResource(R.string.viewer_pdf_page_error, pdfPage.index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry) {
                    Text(text = stringResource(CommonR.string.general_retry_action))
                }
            }
        }

        pdfPage?.bitmap == null -> Box(modifier = modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        else -> ZoomableFileImage(
            modifier = modifier,
            imageSource = remember(pdfPage.bitmap) { BitmapZoomableImageSource(pdfPage.bitmap) },
            fileName = fileName,
            contentDescription = stringResource(
                R.string.viewer_pdf_page_content_description,
                fileName,
                pdfPage.index + 1,
                pageCount,
            ),
            state = zoomableState,
            onClick = onClick,
        )
    }
}

/** Which page of the document is on screen, plus the steps to its neighbours. */
@Composable
fun PdfPageBar(
    modifier: Modifier = Modifier,
    pageIndex: Int,
    pageCount: Int,
    isRendering: Boolean = false,
    onPreviousPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        if (pageCount > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPreviousPage,
                    enabled = !isRendering && pageIndex > 0,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.TwoTone.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.viewer_pdf_page_previous),
                    )
                }
                Text(
                    // Unweighted children are measured first, so the buttons always keep their touch
                    // targets while the text takes only the remainder.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    text = stringResource(R.string.viewer_pdf_page_indicator, pageIndex + 1, pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = onNextPage,
                    enabled = !isRendering && pageIndex < pageCount - 1,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.TwoTone.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.viewer_pdf_page_next),
                    )
                }
            }
        } else {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                text = stringResource(R.string.viewer_pdf_preview_hint_single),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPreviewContentLoadingPreview() {
    PdfPreviewContent(
        pdfPage = ViewerWorkspaceViewModel.PdfPage(index = 0, bitmap = null),
        pageCount = 12,
        fileName = "manual.pdf",
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPreviewContentLoadedPreview() {
    PdfPreviewContent(
        pdfPage = ViewerWorkspaceViewModel.PdfPage(
            index = 2,
            bitmap = remember {
                Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFFF5F5F5.toInt()) }
            },
        ),
        pageCount = 12,
        fileName = "manual.pdf",
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPreviewContentFailedPreview() {
    PdfPreviewContent(
        pdfPage = ViewerWorkspaceViewModel.PdfPage(index = 4, bitmap = null, failed = true),
        pageCount = 12,
        fileName = "manual.pdf",
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPageBarPreview() {
    PdfPageBar(
        modifier = Modifier.padding(16.dp),
        pageIndex = 2,
        pageCount = 104,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPageBarFirstPagePreview() {
    PdfPageBar(
        modifier = Modifier.padding(16.dp),
        pageIndex = 0,
        pageCount = 104,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPageBarLastPagePreview() {
    PdfPageBar(
        modifier = Modifier.padding(16.dp),
        pageIndex = 103,
        pageCount = 104,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPageBarSinglePagePreview() {
    PdfPageBar(
        modifier = Modifier.padding(16.dp),
        pageIndex = 0,
        pageCount = 1,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPageBarRenderingPreview() {
    PdfPageBar(
        modifier = Modifier.padding(16.dp),
        pageIndex = 2,
        pageCount = 104,
        isRendering = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPageBarNarrowPanePreview() {
    Box(
        modifier = Modifier
            .width(220.dp)
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        PdfPageBar(
            pageIndex = 2,
            pageCount = 104,
        )
    }
}
