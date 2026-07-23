package eu.darken.butler.common.compose

import androidx.compose.foundation.Image
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.decode.DataSource
import coil3.request.ImageRequest

@Composable
fun TintedAsyncImage(
    modifier: Modifier = Modifier,
    model: Any?,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    alpha: Float = DefaultAlpha,
    tint: Color = LocalContentColor.current,
) {
    val context = LocalContext.current
    val sizeResolver = rememberConstraintsSizeResolver()

    val request = remember(context, model, sizeResolver) {
        ImageRequest.Builder(context)
            .data(model)
            .size(sizeResolver)
            .build()
    }

    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsState()

    val shouldTint = (state as? AsyncImagePainter.State.Success)
        ?.result?.dataSource == DataSource.MEMORY

    // Guard against an Unspecified ambient content color (e.g. a Card with a custom/alpha
    // container color that yields no derived contentColor), which would paint the icon black.
    val resolvedTint = tint.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant }

    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.then(sizeResolver),
        contentScale = contentScale,
        alignment = alignment,
        alpha = alpha,
        colorFilter = if (shouldTint) {
            ColorFilter.tint(resolvedTint)
        } else null,
    )
}
