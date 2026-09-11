package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FilterAlt
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.ui.explorer.EmptyRecovery
import kotlinx.coroutines.delay

/**
 * Shown when filtering emptied a listing that has content.
 *
 * [recovery] decides what is offered, because it is classified from what each control would
 * actually bring back. A null verdict - the listing is still being typed - gets no button rather
 * than a guess that may turn out to change nothing.
 */
@Composable
fun EmptyFilteredState(
    modifier: Modifier = Modifier,
    recovery: EmptyRecovery?,
    onResetFilters: () -> Unit,
    onShowHidden: () -> Unit,
    onShowAll: () -> Unit,
    initiallyVisible: Boolean = false,
) {
    var visible by remember { mutableStateOf(initiallyVisible) }

    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }

    val infiniteTransition = rememberInfiniteTransition(label = "floating")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(600)),
            exit = fadeOut()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .scale(scale)
                        .alpha(alpha),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.FilterAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(56.dp)
                    )
                }

                Text(
                    text = stringResource(titleFor(recovery)),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp)
                )

                Text(
                    text = stringResource(captionFor(recovery)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )

                when (recovery) {
                    EmptyRecovery.SHOW_HIDDEN -> RecoveryButton(
                        label = R.string.explorer_empty_show_hidden_action,
                        onClick = onShowHidden,
                    )
                    EmptyRecovery.RESET_FILTERS -> RecoveryButton(
                        label = CommonR.string.general_reset_action,
                        onClick = onResetFilters,
                    )
                    // Named rather than "Reset": with two buttons, neither can rely on the caption
                    EmptyRecovery.EITHER -> {
                        RecoveryButton(
                            label = R.string.explorer_empty_show_hidden_action,
                            onClick = onShowHidden,
                        )
                        RecoveryButton(
                            label = R.string.explorer_empty_clear_filters_action,
                            onClick = onResetFilters,
                        )
                    }
                    EmptyRecovery.SHOW_ALL -> RecoveryButton(
                        label = R.string.explorer_empty_show_all_action,
                        onClick = onShowAll,
                    )
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun RecoveryButton(label: Int, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Text(text = stringResource(label))
    }
}

private fun titleFor(recovery: EmptyRecovery?): Int = when (recovery) {
    EmptyRecovery.SHOW_HIDDEN -> R.string.explorer_empty_hidden_title
    EmptyRecovery.EITHER -> R.string.explorer_empty_hidden_or_filtered_title
    EmptyRecovery.SHOW_ALL -> R.string.explorer_empty_hidden_and_filtered_title
    EmptyRecovery.RESET_FILTERS, null -> R.string.explorer_empty_filtered_title
}

private fun captionFor(recovery: EmptyRecovery?): Int = when (recovery) {
    EmptyRecovery.SHOW_HIDDEN -> R.string.explorer_empty_hidden_caption
    EmptyRecovery.EITHER -> R.string.explorer_empty_hidden_or_filtered_caption
    EmptyRecovery.SHOW_ALL -> R.string.explorer_empty_hidden_and_filtered_caption
    EmptyRecovery.RESET_FILTERS, null -> R.string.explorer_empty_filtered_caption
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EmptyFilteredStatePreview() {
    EmptyFilteredState(
        recovery = EmptyRecovery.RESET_FILTERS,
        onResetFilters = {},
        onShowHidden = {},
        onShowAll = {},
        initiallyVisible = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EmptyFilteredStateShowHiddenPreview() {
    EmptyFilteredState(
        recovery = EmptyRecovery.SHOW_HIDDEN,
        onResetFilters = {},
        onShowHidden = {},
        onShowAll = {},
        initiallyVisible = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EmptyFilteredStateEitherPreview() {
    EmptyFilteredState(
        recovery = EmptyRecovery.EITHER,
        onResetFilters = {},
        onShowHidden = {},
        onShowAll = {},
        initiallyVisible = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EmptyFilteredStateShowAllPreview() {
    EmptyFilteredState(
        recovery = EmptyRecovery.SHOW_ALL,
        onResetFilters = {},
        onShowHidden = {},
        onShowAll = {},
        initiallyVisible = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EmptyFilteredStateUnclassifiedPreview() {
    EmptyFilteredState(
        recovery = null,
        onResetFilters = {},
        onShowHidden = {},
        onShowAll = {},
        initiallyVisible = true,
    )
}
