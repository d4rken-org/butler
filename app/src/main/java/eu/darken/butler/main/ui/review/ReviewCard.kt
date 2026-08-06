package eu.darken.butler.main.ui.review

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Asks the user for a Play review. Lowest priority surface on the workspace screen: anything that
 * asks the user for a decision has to win over asking them for a favor.
 *
 * [activity] is what Play's in-app review flow needs; without one the action stays disabled instead
 * of dropping the tap silently.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewCard(
    modifier: Modifier = Modifier,
    activity: Activity?,
    onDismiss: () -> Unit,
    onReview: (Activity) -> Unit,
) {
    var isVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // The card only disappears with the next state emission (or, for a dismiss, once the local hide
    // below is through), so the tap targets need a latch. It is asymmetric on purpose: the harmful
    // orderings are a dismiss after a review (which overwrites the review bookkeeping with a
    // snooze) and a review after a dismiss. A repeated review tap is harmless, the tool's
    // single-flight lock absorbs it, and blocking it here would leave a dead card whenever a Play
    // request fails and nothing gets persisted.
    // Plain remember, not rememberSaveable (deviation from sd-maid-se): the dismissal only reaches
    // the host after the composition-scoped 350ms hide delay, so a recreation inside that window
    // loses the dismissal. Saveable latches would survive it and restore a visible card with both
    // actions dead; with plain remember the card comes back fully usable.
    var dismissLocked by remember { mutableStateOf(false) }
    var fullyLatched by remember { mutableStateOf(false) }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ButlerMascot(
                        modifier = Modifier.size(48.dp),
                        variant = ButlerMascotMode.Static.Happy(),
                    )
                    Text(
                        text = stringResource(R.string.review_app_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                // FlowRow wraps to a new line instead of clipping on narrow widths / large fonts.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Dismissing always persists, so the card can hide right away and report back
                    // once the exit animation is through. The latches close before that: during the
                    // hide the review button is still composed and would otherwise stay tappable.
                    TextButton(
                        onClick = {
                            if (fullyLatched || dismissLocked) return@TextButton
                            dismissLocked = true
                            fullyLatched = true
                            scope.launch {
                                isVisible = false
                                delay(350)
                                onDismiss()
                            }
                        },
                        enabled = !fullyLatched && !dismissLocked,
                    ) {
                        Text(text = stringResource(R.string.review_app_dismiss_action))
                    }

                    // No local hide here: the review flow can fail transiently and persist nothing,
                    // and a locally hidden card would leave the tap unrepeatable. When it does
                    // persist, the state change collapses the card through the exit animation.
                    Button(
                        onClick = {
                            val target = activity ?: return@Button
                            if (fullyLatched) return@Button
                            dismissLocked = true
                            onReview(target)
                        },
                        enabled = activity != null && !fullyLatched,
                    ) {
                        Text(text = stringResource(R.string.review_app_review_action))
                    }
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ReviewCardPreview() {
    ReviewCard(
        activity = null,
        onDismiss = {},
        onReview = {},
    )
}
