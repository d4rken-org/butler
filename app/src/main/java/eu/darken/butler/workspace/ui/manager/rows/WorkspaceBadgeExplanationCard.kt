package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Lightbulb
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material.icons.twotone.Tab
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.manager.FakeWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceBadgeExplanationCard(
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.workspace_badge_tip_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            CompositionLocalProvider(
                LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
                    WorkspaceButtonViewModel.State(
                        workspaceCount = 3,
                        operationsCount = 2,
                        attentionCount = 1,
                    )
                )
            ) {
                WorkspaceButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    buttonSize = 56.dp,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ButlerChip(
                    label = stringResource(R.string.workspace_badge_open_workspaces),
                    leadingIcon = Icons.TwoTone.Tab,
                    size = ButlerChipSize.Default,
                )
                ButlerChip(
                    label = stringResource(R.string.workspace_badge_active_operations),
                    leadingIcon = Icons.TwoTone.Sync,
                    size = ButlerChipSize.Default,
                    colors = ButlerChipDefaults.highlightColors(),
                )
                ButlerChip(
                    label = stringResource(R.string.workspace_badge_attention_items),
                    leadingIcon = Icons.TwoTone.Warning,
                    size = ButlerChipSize.Default,
                    colors = ButlerChipDefaults.errorColors(),
                )
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspaceBadgeExplanationCardPreview() {
    PreviewWrapper {
        WorkspaceBadgeExplanationCard(
            onDismiss = {},
        )
    }
}
