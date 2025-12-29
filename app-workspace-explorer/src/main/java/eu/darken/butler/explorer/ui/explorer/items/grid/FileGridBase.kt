package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.theming.onScrim
import eu.darken.butler.explorer.core.engine.ExplorerItem

@Composable
internal fun FileGridBase(
    modifier: Modifier = Modifier,
    item: ExplorerItem,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean,
    isEnabled: Boolean = true,
    isHighlighted: Boolean = false,
    icon: @Composable () -> Unit,
    primaryText: String,
    secondaryText: String? = null,
    tertiaryText: String? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    previewContent: @Composable (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    // Animate highlight border color
    val highlightBorderColor by animateColorAsState(
        targetValue = if (isHighlighted) {
            MaterialTheme.colorScheme.tertiary
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 300),
        label = "highlightBorderColor",
    )

    // Determine border: selection takes precedence over highlight
    val borderWidth = when {
        isSelected -> 2.dp
        isHighlighted -> 2.dp
        else -> 0.5.dp
    }
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isHighlighted -> highlightBorderColor
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .alpha(if (isEnabled) 1f else 0.38f)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(4.dp)
            )
            .then(
                if (isEnabled) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                } else Modifier
            ),
        shape = RoundedCornerShape(4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Preview/placeholder background
            if (previewContent != null) {
                previewContent()
            }

            // Top bar with icon and size
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon or checkbox in top-left
                Box(
                    modifier = Modifier.size(20.dp)
                ) {
                    if (showSelection) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelection() },
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        icon()
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Secondary info or trailing content in top-right
                when {
                    trailingContent != null -> trailingContent()
                    secondaryText != null -> Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onScrim,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            // Bottom bar with filename
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = primaryText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onScrim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    tertiaryText?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onScrim.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}