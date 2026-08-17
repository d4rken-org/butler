package eu.darken.butler.viewer.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.viewer.R
import kotlinx.coroutines.flow.Flow
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState

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

/** First page of a PDF, zoomable like an image. A null [firstPage] means the render is still running. */
@Composable
fun PdfPreviewContent(
    modifier: Modifier = Modifier,
    firstPage: Bitmap?,
    fileName: String,
    zoomableState: ZoomableState = rememberZoomableState(),
) {
    if (firstPage == null) {
        Box(modifier = modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    } else {
        ZoomableFileImage(
            modifier = modifier,
            imageSource = remember(firstPage) { BitmapZoomableImageSource(firstPage) },
            fileName = fileName,
            contentDescription = stringResource(R.string.viewer_pdf_preview_content_description, fileName),
            state = zoomableState,
        )
    }
}

/** Says that this is a preview of page one, not the whole document - the rest needs "Open with". */
@Composable
fun PdfPreviewHintCard(
    modifier: Modifier = Modifier,
    pageCount: Int,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            text = if (pageCount > 1) {
                stringResource(R.string.viewer_pdf_preview_hint_pages, pageCount)
            } else {
                stringResource(R.string.viewer_pdf_preview_hint_single)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPreviewContentLoadingPreview() {
    PdfPreviewContent(
        firstPage = null,
        fileName = "manual.pdf",
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPreviewContentLoadedPreview() {
    PdfPreviewContent(
        firstPage = remember {
            Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFFF5F5F5.toInt()) }
        },
        fileName = "manual.pdf",
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPreviewHintCardPreview() {
    PdfPreviewHintCard(
        modifier = Modifier
            .width(360.dp)
            .padding(16.dp),
        pageCount = 42,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PdfPreviewHintCardSinglePagePreview() {
    PdfPreviewHintCard(
        modifier = Modifier
            .width(360.dp)
            .padding(16.dp),
        pageCount = 1,
    )
}
