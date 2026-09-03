package eu.darken.butler.bugreport.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.bugreport.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults

/**
 * Static, compact toolbar for the bug-report detail view: back arrow + title + Rename + Share +
 * Delete.
 * It stays at the compact workspace-button size (no expand/collapse) — the larger form just wasted
 * vertical space here. Controls are plain clickable boxes (not IconButton) so the bar height isn't
 * pinned to the 48dp minimum interactive size.
 */
@Composable
fun BugReportDetailToolbarCard(
    modifier: Modifier = Modifier,
    title: String,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarControl(
                icon = Icons.AutoMirrored.TwoTone.ArrowBack,
                contentDescription = stringResource(R.string.bugreport_detail_back_action),
                onClick = onBack,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolbarControl(
                icon = Icons.TwoTone.Edit,
                contentDescription = stringResource(R.string.bugreport_rename_action),
                onClick = onRename,
            )
            ToolbarControl(
                icon = Icons.TwoTone.Share,
                contentDescription = stringResource(R.string.bugreport_share_action),
                onClick = onShare,
            )
            Spacer(modifier = Modifier.width(8.dp))
            ToolbarControl(
                icon = Icons.TwoTone.Delete,
                contentDescription = stringResource(R.string.bugreport_delete_action),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun ToolbarControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    controlSize: Dp = WorkspaceButtonDefaults.sizeCompact,
    iconSize: Dp = 22.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = Modifier
            .size(controlSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Preview2
@Composable
private fun BugReportDetailToolbarCardPreview() {
    PreviewWrapper {
        BugReportDetailToolbarCard(
            title = "NullPointerException",
            onBack = {},
            onRename = {},
            onShare = {},
            onDelete = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
