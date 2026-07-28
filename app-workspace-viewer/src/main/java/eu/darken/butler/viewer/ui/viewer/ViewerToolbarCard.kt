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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
    fullPath: String,
    isCollapsed: Boolean = false,
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
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                            text = stringResource(R.string.viewer_toolbar_path_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            modifier = Modifier
                                .heightIn(max = PathBlockMaxHeight)
                                .verticalScroll(rememberScrollState()),
                            text = fullPath,
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerToolbarCardPreview() {
    ViewerToolbarCard(
        modifier = Modifier.width(360.dp),
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        fileName = "IMG_20240817_183042_HDR.jpg",
        fullPath = "/storage/emulated/0/DCIM/Camera/IMG_20240817_183042_HDR.jpg",
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
        fullPath = "/storage/emulated/0/Android/data/eu.darken.butler/files/backups/2024/august/" +
            "screenshots/pending-upload/a-really-quite-unreasonably-long-screenshot-file-name-2024-08-17.png",
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
        fullPath = "/storage/emulated/0/DCIM/Camera/IMG_20240817_183042_HDR.jpg",
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
        fullPath = "/storage/emulated/0/Download/diagram.svg",
    )
}
