package eu.darken.butler.common.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun BadgedIcon(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    badge: ImageVector?,
    iconSize: Dp = 24.dp,
    badgeSize: Dp = 12.dp,
    iconTint: Color = LocalContentColor.current,
    badgeTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = iconTint,
        )
        if (badge != null) {
            Icon(
                imageVector = badge,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badgeSize)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                tint = badgeTint,
            )
        }
    }
}

@Preview2
@Composable
private fun BadgedIconPreview() {
    PreviewWrapper {
        BadgedIcon(
            icon = Icons.TwoTone.Folder,
            badge = Icons.TwoTone.PauseCircle,
            iconSize = 32.dp,
            badgeSize = 14.dp,
        )
    }
}

@Preview2
@Composable
private fun BadgedIconNoBadgePreview() {
    PreviewWrapper {
        BadgedIcon(
            icon = Icons.TwoTone.Folder,
            badge = null,
            iconSize = 32.dp,
        )
    }
}
