package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AddCircle
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Stars
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerIcon
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.WorkspaceAction

@Composable
internal fun EmptyAdaptiveWorkspaceContent(
    modifier: Modifier = Modifier,
    isUpgraded: Boolean,
    onNavToSettings: () -> Unit,
    onTabAction: (WorkspaceAction.Create) -> Unit,
    onUpgradeButler: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.Companion.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.Companion.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.Companion
                    .size(72.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Companion.Center
            ) {
                ButlerIcon(
                    size = 56.dp
                )
            }

            Column {
                if (isUpgraded) {
                    ColoredTitleText(
                        fullTitle = stringResource(R.string.app_name_upgraded),
                        postfix = stringResource(R.string.app_name_upgrade_postfix),
                    )
                } else {
                    Text(
                        text = stringResource(eu.darken.butler.common.R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(eu.darken.butler.common.R.string.app_name_subtitle),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.Companion.height(32.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Companion.Start
        ) {
            item {
                Card(
                    modifier = Modifier.Companion
                        .fillMaxWidth()
                        .clickable { onTabAction(WorkspaceAction.Create()) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.Companion.padding(20.dp),
                        verticalAlignment = Alignment.Companion.CenterVertically
                    ) {
                        Column(modifier = Modifier.Companion.weight(1f)) {
                            Text(
                                text = stringResource(R.string.workspace_tab_add_action),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.workspace_tab_add_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Icon(imageVector = Icons.TwoTone.AddCircle, contentDescription = null)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.Companion
                        .fillMaxWidth()
                        .clickable { onNavToSettings() }) {
                    Row(
                        modifier = Modifier.Companion.padding(20.dp),
                        verticalAlignment = Alignment.Companion.CenterVertically
                    ) {
                        Column(modifier = Modifier.Companion.weight(1f)) {
                            Text(
                                text = stringResource(R.string.workspace_settings_action),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.workspace_settings_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.TwoTone.Settings,
                            contentDescription = stringResource(R.string.settings_label)
                        )
                    }
                }
            }
            if (!isUpgraded) {
                item {
                    Card(
                        modifier = Modifier.Companion
                            .fillMaxWidth()
                            .clickable { onUpgradeButler() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.Companion.padding(20.dp),
                            verticalAlignment = Alignment.Companion.CenterVertically
                        ) {
                            Column(modifier = Modifier.Companion.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.upgrade_prompt_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = stringResource(R.string.upgrade_prompt_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Icon(
                                imageVector = Icons.TwoTone.Stars,
                                contentDescription = stringResource(R.string.upgrade_prompt_upgrade_action),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun EmptyWorkspaceContentPreview() {
    PreviewWrapper {
        EmptyAdaptiveWorkspaceContent(
            isUpgraded = false,
            onNavToSettings = {},
            onTabAction = {},
            onUpgradeButler = {},
        )
    }
}
