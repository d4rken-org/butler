package eu.darken.butler.viewer.ui.viewer

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/**
 * Roughly five lines at bodySmall. Beyond that the path block scrolls instead of growing, the two
 * floating bar stacks have no collision resolution and a taller toolbar would run into the bottom one.
 */
private val PathBlockMaxHeight = 96.dp

@Composable
fun ViewerToolbarCard(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    fileName: String,
    folderPath: String,
    isCollapsed: Boolean = false,
    onBackClick: (() -> Unit)? = null,
) {
    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) CutoutCardDefaults.ContentPaddingCollapsed else CutoutCardDefaults.ContentPaddingExpanded,
        label = "cardPadding",
    )

    CutoutCard(
        modifier = modifier.fillMaxWidth(),
        cutoutContent = if (design.isSingle) {
            {
                WorkspaceButton(
                    currentWorkspaceId = workspaceId,
                    buttonSize = if (isCollapsed) WorkspaceButtonDefaults.sizeCompact else WorkspaceButtonDefaults.sizeDefault,
                )
            }
        } else null,
        cutoutMode = if (isCollapsed) CutoutMode.FullHeight else CutoutMode.Auto,
        gapDistance = if (isCollapsed) CutoutCardDefaults.GapDistanceCollapsed else CutoutCardDefaults.GapDistanceExpanded,
        contentPadding = CutoutCardDefaults.contentPadding(cardPadding),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (isCollapsed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (onBackClick == null) 8.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onBackClick?.let { ViewerBackButton(onClick = it) }
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = Workspace.Type.VIEWER.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    modifier = Modifier.weight(1f),
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        } else {
            Column {
                // Only the title row dodges the corner notch, the path block below uses the full
                // width - the resulting L-shape is intentional.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = cutoutWidth),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onBackClick?.let {
                        ViewerBackButton(onClick = it)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        modifier = Modifier.size(24.dp),
                        imageVector = Workspace.Type.VIEWER.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        modifier = Modifier.weight(1f),
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        // Single line: multiline MiddleEllipsis silently degrades to clipping on
                        // Android, which would eat the extension this is meant to preserve.
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.viewer_toolbar_folder_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            modifier = Modifier
                                .heightIn(max = PathBlockMaxHeight)
                                .verticalScroll(rememberScrollState()),
                            text = folderPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = true,
                        )
                    }
                }
            }
        }
    }
}

/** Leaves a drill-down viewer; absent when the viewer is a tab of its own. */
@Composable
private fun ViewerBackButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.TwoTone.ArrowBack,
            contentDescription = stringResource(R.string.viewer_back_action),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerBackButtonPreview() {
    ViewerBackButton(onClick = {})
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
        folderPath = "/storage/emulated/0/DCIM/Camera",
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerToolbarCardLongPathPreview() {
    ViewerToolbarCard(
        modifier = Modifier.width(360.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        fileName = "a-really-quite-unreasonably-long-screenshot-file-name-2024-08-17.png",
        folderPath = "/storage/emulated/0/Android/data/eu.darken.butler/files/backups/2024/august/" +
            "screenshots/pending-upload",
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerToolbarCardModalPreview() {
    ViewerToolbarCard(
        modifier = Modifier.width(360.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        fileName = "IMG_20240817_183042_HDR.jpg",
        folderPath = "/storage/emulated/0/DCIM/Camera",
        onBackClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerToolbarCardModalCollapsedPreview() {
    ViewerToolbarCard(
        modifier = Modifier.width(360.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        fileName = "IMG_20240817_183042_HDR.jpg",
        folderPath = "/storage/emulated/0/DCIM/Camera",
        isCollapsed = true,
        onBackClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerToolbarCardCollapsedPreview() {
    ViewerToolbarCard(
        modifier = Modifier.width(360.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        fileName = "IMG_20240817_183042_HDR.jpg",
        folderPath = "/storage/emulated/0/DCIM/Camera",
        isCollapsed = true,
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
        folderPath = "/storage/emulated/0/Download",
    )
}
