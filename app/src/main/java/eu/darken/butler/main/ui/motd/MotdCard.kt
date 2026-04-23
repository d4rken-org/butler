package eu.darken.butler.main.ui.motd

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.main.core.motd.MotdApi
import eu.darken.butler.main.core.motd.MotdState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

@Composable
fun MotdCard(
    motd: MotdState,
    onHide: () -> Unit,
    onMarkAsRead: (Uuid) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = tween(durationMillis = 400)
        ) + fadeIn(
            animationSpec = tween(durationMillis = 400)
        ),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 300)
        ) + fadeOut(
            animationSpec = tween(durationMillis = 300)
        ),
        modifier = modifier,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 6.dp
            ),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = motd.motd.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                isVisible = false
                                delay(350)
                                onHide()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_dismiss_action),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                isVisible = false
                                delay(350)
                                onMarkAsRead(motd.id)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Check,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(eu.darken.butler.common.R.string.general_mark_as_read_action),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    motd.motd.primaryLink?.let { link ->
                        TextButton(
                            onClick = { onLinkClick(link) },
                        ) {
                            Text(
                                text = stringResource(eu.darken.butler.common.R.string.general_read_more_action),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MotdCardPreview() {
    MotdCard(
        motd = MotdState(
            motd = MotdApi.Motd(
                id = Uuid.random(),
                message = "This is a message of the day. It can contain important information about updates, new features, or announcements.",
                primaryLink = "https://example.com",
                minimumVersion = null,
                maximumVersion = null,
            ),
            locale = java.util.Locale.ENGLISH,
        ),
        onHide = {},
        onMarkAsRead = {},
        onLinkClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MotdCardNoLinkPreview() {
    MotdCard(
        motd = MotdState(
            motd = MotdApi.Motd(
                id = Uuid.random(),
                message = "This is a shorter MOTD without a link.",
                primaryLink = null,
                minimumVersion = null,
                maximumVersion = null,
            ),
            locale = java.util.Locale.ENGLISH,
        ),
        onHide = {},
        onMarkAsRead = {},
        onLinkClick = {},
    )
}