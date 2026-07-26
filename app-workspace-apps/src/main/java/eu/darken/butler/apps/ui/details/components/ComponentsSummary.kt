package eu.darken.butler.apps.ui.details.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.core.details.components.ComponentsUiState
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Compact overview of an app's manifest components. Shown inside the App Details overview;
 * tapping "View all" navigates to the dedicated components screen.
 */
@Composable
fun ComponentsSummary(
    modifier: Modifier = Modifier,
    state: ComponentsUiState,
    onViewAll: () -> Unit,
) {
    when (state) {
        ComponentsUiState.Loading -> SummaryMessage(modifier, stringResource(R.string.apps_components_loading))
        ComponentsUiState.Error -> SummaryMessage(modifier, stringResource(R.string.apps_components_error))
        is ComponentsUiState.Ready -> {
            val data = state.data
            if (data.total == 0) {
                SummaryMessage(modifier, stringResource(R.string.apps_components_empty))
            } else {
                Column(modifier = modifier.fillMaxWidth()) {
                    SummaryCountRow(stringResource(R.string.apps_components_activities_label), data.activities.size)
                    SummaryCountRow(stringResource(R.string.apps_components_services_label), data.services.size)
                    SummaryCountRow(stringResource(R.string.apps_components_receivers_label), data.receivers.size)
                    SummaryCountRow(stringResource(R.string.apps_components_providers_label), data.providers.size)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onViewAll)
                            .padding(top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.apps_components_view_all_action),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.apps_components_total_label, data.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.TwoTone.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCountRow(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryMessage(
    modifier: Modifier = Modifier,
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentsSummaryPreview() {
    ComponentsSummary(
        state = ComponentsUiState.Ready(previewComponentsData),
        onViewAll = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentsSummaryEmptyPreview() {
    ComponentsSummary(
        state = ComponentsUiState.Ready(ComponentsData()),
        onViewAll = {},
    )
}
