package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.apps.core.engine.FilterState
import eu.darken.butler.apps.core.engine.getTagState
import eu.darken.butler.apps.core.engine.standardTags
import eu.darken.butler.apps.core.engine.withTagState
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
    modifier: Modifier = Modifier,
    filterConfig: TagFilterConfig,
    availableTags: List<AppTag>,
    onFilterChange: (TagFilterConfig) -> Unit,
    onDismiss: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        bottomInset = bottomInset,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header with title and reset
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.apps_action_filter),
                    style = MaterialTheme.typography.titleLarge,
                )

                TextButton(
                    onClick = { onFilterChange(TagFilterConfig()) },
                    enabled = !filterConfig.isEmpty,
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_reset_action))
                }
            }

            // Hint text
            Text(
                text = stringResource(R.string.apps_filter_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Tag chips
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availableTags.forEach { tag ->
                    key(tag) {
                        TriStateFilterChip(
                            tag = tag,
                            state = filterConfig.getTagState(tag),
                            onStateChange = { newState ->
                                onFilterChange(filterConfig.withTagState(tag, newState))
                            },
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
private fun FilterBottomSheetEmptyPreview() {
    FilterBottomSheet(
        filterConfig = TagFilterConfig(),
        availableTags = AppTag.standardTags,
        onFilterChange = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FilterBottomSheetWithFiltersPreview() {
    FilterBottomSheet(
        filterConfig = TagFilterConfig(
            includeTags = setOf(AppTag.UserApp, AppTag.Enabled),
            excludeTags = setOf(AppTag.Debug),
        ),
        availableTags = AppTag.standardTags,
        onFilterChange = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FilterBottomSheetWithUserTagPreview() {
    FilterBottomSheet(
        filterConfig = TagFilterConfig(
            includeTags = setOf(AppTag.User(handleId = 10, label = "Work")),
        ),
        availableTags = AppTag.standardTags + AppTag.User(handleId = 10, label = "Work"),
        onFilterChange = {},
        onDismiss = {},
    )
}
