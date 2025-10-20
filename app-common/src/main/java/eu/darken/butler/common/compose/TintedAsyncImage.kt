package eu.darken.butler.common.compose

import androidx.compose.foundation.Image
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.decode.DataSource

@Composable
fun TintedAsyncImage(
    modifier: Modifier = Modifier,
    model: Any?,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    alpha: Float = DefaultAlpha,
) {
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        alpha = alpha,
    ) {
        val state by painter.state.collectAsState()
        when (val stateSnap = state) {
            is AsyncImagePainter.State.Success -> {
                val shouldTint = stateSnap.result.dataSource == DataSource.MEMORY
                Image(
                    painter = painter,
                    contentDescription = contentDescription,
                    modifier = Modifier.matchParentSize(),
                    contentScale = contentScale,
                    alignment = alignment,
                    alpha = alpha,
                    colorFilter = if (shouldTint) ColorFilter.tint(LocalContentColor.current) else null,
                )
            }
            else -> SubcomposeAsyncImageContent()
        }
    }
}
