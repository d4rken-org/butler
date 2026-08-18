package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.viewer.R
import kotlinx.coroutines.flow.Flow
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Renders the image handed down from the ViewModel. Telephoto supplies pinch/pan/double-tap/fling
 * and bounds clamping.
 *
 * Decode failures are not reported from here: [ZoomableImage] builds its sub-sampling state
 * internally and offers no way to pass an error reporter in. Unreadable and undecodable files are
 * caught earlier instead - by the workspace's lookup and its dimension probe - and resolve to a
 * failure the page can explain and retry.
 */
@Composable
fun ZoomableFileImage(
    modifier: Modifier = Modifier,
    imageSource: ZoomableImageSource,
    fileName: String,
    contentDescription: String? = stringResource(R.string.viewer_image_content_description, fileName),
    state: ZoomableState = rememberZoomableState(),
    onClick: (() -> Unit)? = null,
) {
    val imageState = rememberZoomableImageState(state)
    // Telephoto swallows pointer input, so Modifier.clickable never fires on this composable - its
    // own onClick is the only way in. Remembered so the gesture node is not rebuilt per frame.
    val clickHandler = remember(onClick) { onClick?.let { handler -> { _: Offset -> handler() } } }

    ZoomableImage(
        modifier = modifier.fillMaxSize(),
        image = imageSource,
        contentDescription = contentDescription,
        state = imageState,
        onClick = clickHandler,
    )
}

private class PainterZoomableImageSource(
    private val color: Color,
) : ZoomableImageSource {

    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult =
        ZoomableImageSource.ResolveResult(
            delegate = ZoomableImageSource.PainterDelegate(ColorPainter(color)),
        )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ZoomableFileImagePreview() {
    ZoomableFileImage(
        imageSource = PainterZoomableImageSource(Color(0xFF3F51B5)),
        fileName = "IMG_20240817_183042.jpg",
    )
}
