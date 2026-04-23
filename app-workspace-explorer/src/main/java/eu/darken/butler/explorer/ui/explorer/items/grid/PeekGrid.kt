package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun PeekGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Peek,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "peek_loading")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "peek_alpha"
    )

    FileGridBase(
        modifier = modifier.alpha(alpha),
        item = item,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        onLongClick = {},
        showSelection = false,
        icon = {
            Icon(
                imageVector = Icons.TwoTone.HourglassEmpty,
                contentDescription = stringResource(R.string.explorer_file_peek_content_desc),
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = null,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PeekGridPreview() {
    PeekGrid(
        item = MockDataProvider.createMockPeek()
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PeekGridSelectedPreview() {
    PeekGrid(
        item = MockDataProvider.createMockPeek("loading.txt")
    )
}