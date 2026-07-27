package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.OpenInBrowser
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.viewer.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.common.CutoutCard
import eu.darken.butler.workspace.ui.common.CutoutCardDefaults
import eu.darken.butler.workspace.ui.common.CutoutMode
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun ViewerToolbarCard(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    fileName: String,
    parentPath: String?,
    onOpenWith: () -> Unit,
) {
    CutoutCard(
        modifier = modifier.fillMaxWidth(),
        cutoutContent = if (design.isSingle) {
            {
                WorkspaceButton(
                    currentWorkspaceId = workspaceId,
                    buttonSize = WorkspaceButtonDefaults.sizeDefault,
                )
            }
        } else {
            null
        },
        cutoutMode = CutoutMode.Auto,
        contentPadding = CutoutCardDefaults.contentPadding(CutoutCardDefaults.ContentPaddingExpanded),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = cutoutWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Workspace.Type.VIEWER.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (parentPath != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = parentPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
            IconButton(
                onClick = onOpenWith,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.OpenInBrowser,
                    contentDescription = stringResource(R.string.viewer_open_with_action),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerToolbarCardPreview() {
    ViewerToolbarCard(
        modifier = Modifier.width(360.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        fileName = "IMG_20240817_183042_HDR.jpg",
        parentPath = "/storage/emulated/0/DCIM/Camera",
        onOpenWith = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerToolbarCardLongNamePreview() {
    ViewerToolbarCard(
        modifier = Modifier.width(360.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        fileName = "a-really-quite-unreasonably-long-screenshot-file-name-2024-08-17.png",
        parentPath = "/storage/emulated/0/Pictures/Screenshots",
        onOpenWith = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerToolbarCardSplitPanePreview() {
    ViewerToolbarCard(
        modifier = Modifier.width(360.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
        fileName = "diagram.svg",
        parentPath = "/storage/emulated/0/Download",
        onOpenWith = {},
    )
}
