package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ErrorOutline
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerExternalChange
import eu.darken.butler.common.R as CommonR

/**
 * Says that the file underneath the viewer moved on, and offers the only answer to it.
 *
 * Refresh is the single action on purpose: nothing here holds the old bytes, and lazy PDF page and
 * image tile reads mean part of what is on screen may already come from the new file.
 */
@Composable
fun ViewerExternalChangeBanner(
    modifier: Modifier = Modifier,
    change: ViewerExternalChange,
    onRefresh: () -> Unit,
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (change) {
                        ViewerExternalChange.Modified -> Icons.TwoTone.Sync
                        ViewerExternalChange.Gone -> Icons.TwoTone.ErrorOutline
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            when (change) {
                                ViewerExternalChange.Modified -> R.string.viewer_external_change_title
                                ViewerExternalChange.Gone -> R.string.viewer_external_change_gone_title
                            }
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource(
                            when (change) {
                                ViewerExternalChange.Modified -> R.string.viewer_external_change_message
                                ViewerExternalChange.Gone -> R.string.viewer_external_change_gone_message
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(CommonR.string.general_refresh_action))
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerExternalChangeBannerPreview() {
    ViewerExternalChangeBanner(
        change = ViewerExternalChange.Modified,
        onRefresh = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerExternalChangeBannerGonePreview() {
    ViewerExternalChangeBanner(
        change = ViewerExternalChange.Gone,
        onRefresh = {},
    )
}
