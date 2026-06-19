package eu.darken.butler.common.debug.recorder.ui.banner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.R
import eu.darken.butler.common.DurationFormat
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatDuration
import eu.darken.butler.common.formatFileSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable fun RecordingBanner(
    modifier: Modifier = Modifier,
    visible: Boolean,
    expanded: Boolean = false,
    elapsedTime: Duration,
    logSize: Long,
    onStop: () -> Unit,
    onView: () -> Unit = {},
) {
    var isExpanded by remember { mutableStateOf(expanded) }
    LaunchedEffect(visible) {
        if (visible) isExpanded = false
    }
    val cardPadding by animateDpAsState(
        targetValue = if (isExpanded) 8.dp else 4.dp, label = "cardPadding"
    )
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(bottomEnd = 12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            shadowElevation = 4.dp,
            modifier = Modifier.clickable { isExpanded = !isExpanded },
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .padding(cardPadding),
            ) {
                // Header - changes based on expanded state
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulsingRecordingDot()

                    Text(
                        text = stringResource(
                            if (isExpanded) {
                                R.string.debug_banner_label_expanded
                            } else {
                                R.string.debug_banner_label
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        letterSpacing = if (isExpanded) 0.sp else 1.sp,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }

                // Expanded content
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        // Explanation in a box
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .padding(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.debug_banner_explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(modifier = Modifier.size(16.dp))
                        // Time and size on same line
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatDuration(elapsedTime, DurationFormat.COMPACT),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )

                            Text(
                                text = "•",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f),
                            )

                            Text(
                                text = formatFileSize(logSize),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }

                        // Action row - View (left) opens the Bug reports workspace, Stop (right)
                        // ends the recording.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onView) {
                                Text(
                                    text = stringResource(R.string.debug_banner_view_action),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            TextButton(onClick = onStop) {
                                Text(
                                    text = stringResource(R.string.debug_banner_stop_action),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PulsingRecordingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording_alpha",
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(
                color = MaterialTheme.colorScheme.error,
                shape = CircleShape,
            ),
    )
}

@Preview2 @Composable private fun RecordingBannerCollapsedPreview() {
    PreviewWrapper {
        RecordingBanner(
            visible = true,
            expanded = false,
            elapsedTime = 5.minutes + 32.seconds,
            logSize = 1_234_567L,
            onStop = {},
        )
    }
}

@Preview2 @Composable private fun RecordingBannerExpandedPreview() {
    PreviewWrapper {
        // Note: In preview we can't easily show expanded state
        // This preview shows the collapsed state
        RecordingBanner(
            visible = true,
            expanded = true,
            elapsedTime = 2.hours + 15.minutes + 8.seconds,
            logSize = 45_678_901L,
            onStop = {},
        )
    }
}
