package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.sizes.DirectorySize

/** A folder's calculated size, marked as a lower bound when part of the folder could not be read. */
@Composable
internal fun directorySizeLabel(size: DirectorySize): String = formatFileSize(size.bytes).let {
    when {
        size.isEstimated && size.hasUnestimatedContent -> stringResource(R.string.explorer_file_size_estimated_partial, it)
        size.isEstimated -> stringResource(R.string.explorer_file_size_estimated, it)
        !size.isComplete -> stringResource(R.string.explorer_file_size_partial, it)
        else -> it
    }
}

/** How this folder's size compares to the largest one in the listing. */
@Composable
internal fun SizeProportionBar(
    modifier: Modifier = Modifier,
    fraction: Float,
    isComplete: Boolean,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = if (isComplete) 1f else 0.5f),
                    shape,
                ),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SizeProportionBarPreview() {
    SizeProportionBar(fraction = 0.6f, isComplete = true)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SizeProportionBarPartialPreview() {
    SizeProportionBar(fraction = 0.3f, isComplete = false)
}
