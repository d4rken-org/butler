package eu.darken.butler.apps.ui.apps.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.apps.core.engine.AppTag
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun AppTagIndicators(
    tags: List<AppTag>,
    modifier: Modifier = Modifier,
    maxIndicators: Int = 3,
) {
    if (tags.isEmpty()) return

    val displayTags = tags.take(maxIndicators)
    val overflow = tags.size - maxIndicators

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        displayTags.forEach { tag ->
            AppTagDot(tag = tag)
        }
        if (overflow > 0) {
            Text(
                text = "+$overflow",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun AppTagDot(
    tag: AppTag,
    modifier: Modifier = Modifier,
) {
    val colors = tag.colors()
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(colors.container),
    )
}

@Preview2
@Composable
private fun AppTagIndicatorsSinglePreview() {
    PreviewWrapper {
        AppTagIndicators(tags = listOf(AppTag.System))
    }
}

@Preview2
@Composable
private fun AppTagIndicatorsMultiplePreview() {
    PreviewWrapper {
        AppTagIndicators(
            tags = listOf(
                AppTag.Disabled,
                AppTag.System,
                AppTag.SplitApk,
            )
        )
    }
}

@Preview2
@Composable
private fun AppTagIndicatorsOverflowPreview() {
    PreviewWrapper {
        AppTagIndicators(
            tags = listOf(
                AppTag.Disabled,
                AppTag.System,
                AppTag.Sideloaded,
                AppTag.Debug,
                AppTag.SplitApk,
            )
        )
    }
}
