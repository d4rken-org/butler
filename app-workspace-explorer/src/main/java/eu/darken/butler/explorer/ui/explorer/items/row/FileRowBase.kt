package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.isProblematicInvisible
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.ItemDecorations
import eu.darken.butler.explorer.ui.explorer.items.LeadingIconSlot
import eu.darken.butler.explorer.ui.explorer.items.rowBadgeSize
import eu.darken.butler.explorer.ui.explorer.items.rowIconGap
import eu.darken.butler.explorer.ui.explorer.items.rowIconSize
import eu.darken.butler.explorer.ui.explorer.items.rowPadding
import eu.darken.butler.explorer.ui.explorer.items.rowVerticalPadding

@Composable
private fun TertiaryMeta(
    modifier: Modifier = Modifier,
    icon: RowMetaIcon?,
    text: String,
    color: Color,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon.icon,
                contentDescription = icon.contentDescription,
                modifier = Modifier.size(14.dp),
                tint = color,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun String.withProblematicCharsUnderlined(color: Color): AnnotatedString {
    if (this.trim { it.isProblematicInvisible() } == this) return AnnotatedString(this)

    return buildAnnotatedString {
        append(this@withProblematicCharsUnderlined)

        // Underline leading problematic characters
        val leadingCount = this@withProblematicCharsUnderlined.takeWhile { it.isProblematicInvisible() }.length
        if (leadingCount > 0) {
            addStyle(
                style = SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    color = color,
                ),
                start = 0,
                end = leadingCount,
            )
        }

        // Underline trailing problematic characters
        val trailingStart = this@withProblematicCharsUnderlined.length -
            this@withProblematicCharsUnderlined.takeLastWhile { it.isProblematicInvisible() }.length
        if (trailingStart < this@withProblematicCharsUnderlined.length) {
            addStyle(
                style = SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    color = color,
                ),
                start = trailingStart,
                end = this@withProblematicCharsUnderlined.length,
            )
        }
    }
}

/**
 * A glyph standing in for a field label on the tertiary line, where "Created 31.12.2026 13:49:07"
 * and its modification counterpart do not both fit. [contentDescription] carries the label that
 * the glyph replaces, so the field is still named to assistive tech.
 */
internal data class RowMetaIcon(
    val icon: ImageVector,
    val contentDescription: String,
)

@Composable
internal fun FileRowBase(
    modifier: Modifier = Modifier,
    item: ExplorerItem,
    density: ExplorerViewStyle.Density,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean,
    isEnabled: Boolean = true,
    isHighlighted: Boolean = false,
    decorations: ItemDecorations = ItemDecorations(),
    leadingContent: @Composable () -> Unit,
    primaryText: String,
    secondaryText: String? = null,
    secondaryEndText: String? = null,
    tertiaryText: String? = null,
    tertiaryIcon: RowMetaIcon? = null,
    tertiaryEndText: String? = null,
    tertiaryEndIcon: RowMetaIcon? = null,
    /** Overrides the muted default, for a tertiary line that carries a state worth noticing. */
    tertiaryColor: Color? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    /** Composed below the text lines, e.g. a size proportion bar. */
    bottomContent: (@Composable () -> Unit)? = null,
    hasProblematicChars: Boolean = false,
) {
    // Animate highlight background color
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 300),
        label = "highlightColor",
    )

    // Determine background: selection takes precedence over highlight
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        isHighlighted -> highlightColor
        else -> Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isEnabled) 1f else 0.38f)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .then(
                if (isEnabled) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                } else Modifier
            )
            .padding(horizontal = density.rowPadding, vertical = density.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading content area - shows either checkbox or decorated icon. Decorations
        // (favorite, etc.) only apply to the icon branch — selection mode swaps in a
        // checkbox and intentionally hides decorations.
        if (showSelection) {
            Box(
                modifier = Modifier.size(density.rowIconSize),
                contentAlignment = Alignment.Center,
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
            }
        } else {
            LeadingIconSlot(
                modifier = Modifier.size(density.rowIconSize),
                decorations = decorations,
                badgeSize = density.rowBadgeSize,
            ) {
                leadingContent()
            }
        }

        Spacer(modifier = Modifier.width(density.rowIconGap))

        // File information
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (hasProblematicChars) {
                    primaryText.withProblematicCharsUnderlined(MaterialTheme.colorScheme.error)
                } else {
                    AnnotatedString(primaryText)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (secondaryText != null || secondaryEndText != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = secondaryText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    if (secondaryEndText != null) {
                        if (!secondaryText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = secondaryEndText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (tertiaryText != null || tertiaryEndText != null) {
                val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TertiaryMeta(
                        modifier = Modifier.weight(1f),
                        icon = tertiaryIcon,
                        text = tertiaryText.orEmpty(),
                        color = tertiaryColor ?: mutedColor,
                    )

                    if (tertiaryEndText != null) {
                        if (!tertiaryText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TertiaryMeta(
                            // Weighted so two timestamps share the line instead of the end one
                            // taking its full width and leaving the other without a date.
                            modifier = Modifier.weight(1f, fill = false),
                            icon = tertiaryEndIcon,
                            text = tertiaryEndText,
                            color = mutedColor,
                        )
                    }
                }
            }

            bottomContent?.let {
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    it()
                }
            }
        }

        // Trailing content area (optional)
        trailingContent?.let {
            Spacer(modifier = Modifier.width(8.dp))
            it()
        }
    }
}