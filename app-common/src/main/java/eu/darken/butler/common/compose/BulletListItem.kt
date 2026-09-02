package eu.darken.butler.common.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.withSoftBreaks

/**
 * A bullet point whose text hangs indented rather than wrapping underneath the bullet.
 *
 * Intended for lists of names the user has to read to confirm a destructive action, so the text is
 * never truncated. Long tokens are wrapped at their separators, see [withSoftBreaks].
 */
@Composable
fun BulletListItem(
    modifier: Modifier = Modifier,
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The bullet and the text are separate nodes, announce them as one item
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = "•",
            style = style,
            color = color,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = text.withSoftBreaks(),
            style = style,
            color = color,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BulletListItemPreview() {
    Column(modifier = Modifier.padding(16.dp)) {
        BulletListItem(text = "termux-app_v0.118.3+github-debug_universal.apk")
        BulletListItem(text = "short.txt")
        BulletListItem(
            text = "A name with ordinary spaces that also needs more than a single line to fit",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
