package eu.darken.butler.workspace.ui.common

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import kotlin.math.roundToInt

private const val AlphaRampFraction = 0.5f
private const val MinScale = 0.6f
private val IndicatorContainerSize = 40.dp
private val SpinnerSize = 20.dp
private val SpinnerStrokeWidth = 2.5.dp

/**
 * Pull-to-refresh for workspace pages, with an indicator that pops in at a fixed anchor instead of
 * travelling down the screen as the user pulls.
 *
 * Material3's own `Indicator`/`IndicatorBox` apply that translation through a non-public modifier,
 * so it cannot be turned off via parameters - hence the custom indicator. Its anchor follows
 * [topBarStackState], so it appears just below the top floating bar stack rather than emerging
 * from behind it mid-pull.
 *
 * Gesture handling, threshold and [onRefresh] semantics are Material3's, unchanged.
 */
@Composable
fun WorkspacePullToRefreshBox(
    modifier: Modifier = Modifier,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    topBarStackState: FloatingBarStackState,
    state: PullToRefreshState = rememberPullToRefreshState(),
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            WorkspacePullToRefreshIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(x = 0, y = topBarStackState.contentPaddingPx.roundToInt()) },
                progress = { state.distanceFraction },
                isRefreshing = isRefreshing,
            )
        },
        content = content,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePullToRefreshBoxPreview() {
    PreviewWrapper {
        WorkspacePullToRefreshBox(
            isRefreshing = true,
            onRefresh = {},
            topBarStackState = rememberFloatingBarStackState(position = BarPosition.TOP),
            content = {},
        )
    }
}

/**
 * The fixed-anchor indicator: fades and scales in with the pull, never translates.
 *
 * Composed only while it has something to show - at rest it contributes no drawing, no semantics
 * and no touch target, which matters because it sits over the first row of content.
 */
@Composable
internal fun WorkspacePullToRefreshIndicator(
    modifier: Modifier = Modifier,
    progress: () -> Float,
    isRefreshing: Boolean,
) {
    val currentProgress by rememberUpdatedState(progress)
    val pulling by remember { derivedStateOf { currentProgress() > 0f } }

    if (pulling || isRefreshing) {
        Box(
            modifier = modifier
                .size(IndicatorContainerSize)
                .graphicsLayer {
                    // Pinned while refreshing: M3 animates the pull distance back to hidden on
                    // release and only returns to the threshold once isRefreshing has reached
                    // composition, which would otherwise show as a visible dip and rebound.
                    val fraction = if (isRefreshing) 1f else currentProgress().coerceIn(0f, 1f)
                    alpha = (fraction / AlphaRampFraction).coerceIn(0f, 1f)
                    scaleX = MinScale + (1f - MinScale) * fraction
                    scaleY = scaleX
                    shape = PullToRefreshDefaults.indicatorShape
                    clip = true
                    shadowElevation = PullToRefreshDefaults.Elevation.toPx()
                }
                .background(
                    color = PullToRefreshDefaults.indicatorContainerColor,
                    shape = PullToRefreshDefaults.indicatorShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = isRefreshing) { refreshing ->
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(SpinnerSize),
                        color = PullToRefreshDefaults.indicatorColor,
                        strokeWidth = SpinnerStrokeWidth,
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { currentProgress().coerceIn(0f, 1f) },
                        modifier = Modifier.size(SpinnerSize),
                        color = PullToRefreshDefaults.indicatorColor,
                        strokeWidth = SpinnerStrokeWidth,
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePullToRefreshIndicatorPullingPreview() {
    PreviewWrapper {
        WorkspacePullToRefreshIndicator(
            progress = { 0.5f },
            isRefreshing = false,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePullToRefreshIndicatorRefreshingPreview() {
    PreviewWrapper {
        WorkspacePullToRefreshIndicator(
            progress = { 1f },
            isRefreshing = true,
        )
    }
}
