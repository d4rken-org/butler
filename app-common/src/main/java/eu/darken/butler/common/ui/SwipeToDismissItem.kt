package eu.darken.butler.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * A reusable swipe-to-dismiss composable that provides consistent behavior across the app.
 *
 * @param modifier Modifier for the SwipeToDismissBox
 * @param onDismiss Called after the swipe has settled in the dismissed position. Must not be
 *   called mid-gesture: removing the item while the pointer is still down detaches the drag node
 *   and leaks the remaining gesture to enclosing scrollables (e.g. the workspace pager).
 * @param enabled Whether swipe gestures are enabled (default: true)
 * @param dismissThreshold Fraction of the width that must be swiped to trigger dismiss (0.0 to 1.0)
 * @param backgroundShape Shape for the background (e.g., RoundedCornerShape for rounded corners)
 * @param backgroundColor Background color when swiping
 * @param contentColor Content color for icons/text in the background
 * @param horizontalPadding Horizontal padding for the background content
 * @param verticalPadding Vertical padding for the background content
 * @param programmaticDismissTrigger Trigger value for programmatic dismiss (0 = disabled)
 * @param programmaticDismissDelay Delay before programmatic dismiss in milliseconds
 * @param dismissContent Content to show in the background when swiping (icon + text)
 * @param content The main content that can be swiped
 */
@Composable
fun SwipeToDismissItem(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    dismissThreshold: Float = 0.5f,
    backgroundShape: Shape = RectangleShape,
    backgroundColor: Color = MaterialTheme.colorScheme.error,
    contentColor: Color = MaterialTheme.colorScheme.onError,
    horizontalPadding: Int = 16,
    verticalPadding: Int = 0,
    programmaticDismissTrigger: Long = 0L,
    programmaticDismissDelay: Long = 0L,
    dismissContent: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * dismissThreshold },
    )

    // Stable identity: m3 keys its settle-effect on the onDismiss lambda, an unstable caller
    // lambda could otherwise restart it and re-fire for an already dismissed item.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val settledDismissCallback = remember { { _: SwipeToDismissBoxValue -> currentOnDismiss() } }

    // Handle programmatic dismiss trigger (e.g., for clear all animations)
    if (programmaticDismissTrigger > 0L) {
        val wasEnabledWhenTriggered = remember(programmaticDismissTrigger) { enabled }
        LaunchedEffect(programmaticDismissTrigger) {
            if (wasEnabledWhenTriggered) {
                if (programmaticDismissDelay > 0L) {
                    kotlinx.coroutines.delay(programmaticDismissDelay)
                }
                dismissState.dismiss(SwipeToDismissBoxValue.EndToStart)
            }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        gesturesEnabled = enabled,
        onDismiss = settledDismissCallback,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(backgroundShape)
                    .background(backgroundColor)
                    .padding(
                        horizontal = horizontalPadding.dp,
                        vertical = verticalPadding.dp
                    ),
                contentAlignment = when (dismissState.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    else -> Alignment.CenterEnd
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = dismissContent
                )
            }
        }
    ) {
        content()
    }
}