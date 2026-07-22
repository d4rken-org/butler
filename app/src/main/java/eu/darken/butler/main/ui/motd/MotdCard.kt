package eu.darken.butler.main.ui.motd

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

@OptIn(ExperimentalLayoutApi::class)
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header: info icon (left), centered "Announcement", close (right)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.TwoTone.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    Text(
                        text = stringResource(eu.darken.butler.R.string.motd_card_header),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                isVisible = false
                                delay(350)
                                onHide()
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_dismiss_action),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                motd.motd.title?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = motd.motd.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )

                motd.motd.description?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Actions: "Mark as read" (secondary, left), "Read more" (primary, right).
                // FlowRow wraps to a new line instead of clipping on narrow widths / large fonts.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        )
                        Text(text = stringResource(eu.darken.butler.common.R.string.general_mark_as_read_action))
                    }

                    motd.motd.primaryLink?.let { link ->
                        Button(
                            onClick = { onLinkClick(link) },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.TwoTone.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(end = 4.dp),
                            )
                            Text(text = stringResource(eu.darken.butler.common.R.string.general_read_more_action))
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
private fun MotdCardFullPreview() {
    MotdCard(
        motd = MotdState(
            motd = MotdApi.Motd(
                id = Uuid.random(),
                title = "New in v0.20",
                message = "This is a message of the day. It can contain important information about updates, new features, or announcements.",
                description = "Tap “Read more” for the full changelog and details.",
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
private fun MotdCardMessageOnlyPreview() {
    MotdCard(
        motd = MotdState(
            motd = MotdApi.Motd(
                id = Uuid.random(),
                message = "This is a shorter MOTD without a title, description, or link.",
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
