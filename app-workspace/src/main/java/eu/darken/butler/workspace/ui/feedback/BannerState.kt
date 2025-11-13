package eu.darken.butler.workspace.ui.feedback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Represents the state of workspace feedback banners.
 *
 * Banners provide non-blocking feedback about workspace operations, typically
 * auto-dismissing after a few seconds while allowing manual dismissal.
 */
sealed interface BannerState {
    /**
     * Success state - all operations completed successfully.
     *
     * @param count Number of successful operations
     */
    data class Success(val count: Int) : BannerState

    /**
     * Partial success state - some operations succeeded, others failed or were skipped.
     *
     * @param success Number of successful operations
     * @param failed Number of failed operations
     * @param skipped Number of skipped operations
     */
    data class Partial(
        val success: Int,
        val failed: Int,
        val skipped: Int,
    ) : BannerState
}

/**
 * The number of successful operations for display purposes.
 */
val BannerState.successCount: Int
    get() = when (this) {
        is BannerState.Success -> count
        is BannerState.Partial -> success
    }

/**
 * The icon to display for this banner state.
 */
val BannerState.icon: ImageVector
    get() = when (this) {
        is BannerState.Success -> Icons.TwoTone.CheckCircle
        is BannerState.Partial -> Icons.TwoTone.Warning
    }

/**
 * The container color for this banner state's card.
 */
val BannerState.containerColor: Color
    @Composable get() = when (this) {
        is BannerState.Success -> MaterialTheme.colorScheme.primaryContainer
        is BannerState.Partial -> MaterialTheme.colorScheme.tertiaryContainer
    }

/**
 * The content color (text and icons) for this banner state.
 */
val BannerState.contentColor: Color
    @Composable get() = when (this) {
        is BannerState.Success -> MaterialTheme.colorScheme.onPrimaryContainer
        is BannerState.Partial -> MaterialTheme.colorScheme.onTertiaryContainer
    }
