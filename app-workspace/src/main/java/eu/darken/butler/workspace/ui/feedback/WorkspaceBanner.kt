package eu.darken.butler.workspace.ui.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import kotlinx.coroutines.delay

@Composable
fun WorkspaceBanner(
    state: BannerState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state != null,
        enter = slideInVertically(animationSpec = tween(300)) { it },
        exit = slideOutVertically(animationSpec = tween(300)) { it },
        modifier = modifier
    ) {
        state?.let { bannerState ->
            // Auto-dismiss after 3 seconds
            LaunchedEffect(bannerState) {
                delay(3000)
                onDismiss()
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = bannerState.containerColor,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = bannerState.icon,
                        contentDescription = null,
                        tint = bannerState.contentColor
                    )

                    Text(
                        text = pluralStringResource(
                            R.plurals.workspace_banner_opened_tabs,
                            bannerState.successCount,
                            bannerState.successCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = bannerState.contentColor,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = stringResource(
                                eu.darken.butler.common.R.string.general_dismiss_action
                            ),
                            tint = bannerState.contentColor
                        )
                    }
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceBannerSuccessPreview() {
    WorkspaceBanner(
        state = BannerState.Success(count = 5),
        onDismiss = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceBannerPartialPreview() {
    WorkspaceBanner(
        state = BannerState.Partial(success = 3, failed = 1, skipped = 2),
        onDismiss = {}
    )
}
