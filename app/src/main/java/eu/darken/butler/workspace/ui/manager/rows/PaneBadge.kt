package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun PaneBadge(
    modifier: Modifier = Modifier,
    paneNumber: Int,
) {
    Surface(
        modifier = modifier.size(18.dp),
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${paneNumber + 1}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBadgePreview() {
    PaneBadge(paneNumber = 0)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBadgePreview2() {
    PaneBadge(paneNumber = 1)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBadgePreview3() {
    PaneBadge(paneNumber = 2)
}
