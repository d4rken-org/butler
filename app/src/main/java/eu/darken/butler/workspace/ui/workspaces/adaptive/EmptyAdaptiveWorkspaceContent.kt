package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AddCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerAppTitle
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.ButlerTip
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.Preview2Tablet
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.guidedTourTarget
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.*
import eu.darken.butler.workspace.ui.workspaces.tour.FirstTabTour

@Composable
internal fun EmptyAdaptiveWorkspaceContent(
    modifier: Modifier = Modifier,
    paneNumber: Int,
    paneEdges: PaneEdges = PaneEdges.All,
    isUpgraded: Boolean = false,
    isTourTarget: Boolean = false,
    onAddWorkspace: (() -> Unit)? = null,
) {
    // Vertical only - horizontal insets are applied by the pane host.
    val paneInsets = paneEdges.paneInsets()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = paneInsets.top, bottom = paneInsets.bottom)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Title bar: Mascot + Title/Subtitle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ButlerMascot(
                    modifier = Modifier.size(72.dp),
                    variant = ButlerMascotMode.Animated.RandomCycling(),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ButlerAppTitle(
                        isUpgraded = isUpgraded,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.workspace_adaptive_pane_ready, paneNumber),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ButlerTip()

            // Add tab button
            if (onAddWorkspace != null) {
                Spacer(modifier = Modifier.size(4.dp))
                Card(
                    onClick = onAddWorkspace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isTourTarget) {
                                Modifier.guidedTourTarget(FirstTabTour.CREATE_TAB_TARGET)
                            } else {
                                Modifier
                            },
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_adaptive_add_action),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.TwoTone.AddCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
private fun EmptyWorkspaceContentPreview() {
    EmptyAdaptiveWorkspaceContent(
        paneNumber = 2,
        onAddWorkspace = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EmptyWorkspaceContentUpgradedPreview() {
    EmptyAdaptiveWorkspaceContent(
        paneNumber = 2,
        isUpgraded = true,
        onAddWorkspace = {},
    )
}

@Preview2Tablet
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EmptyWorkspaceContentPreviewTablet() {
    EmptyAdaptiveWorkspaceContent(
        paneNumber = 2,
        onAddWorkspace = {},
    )
}
