package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.engine.AppTag
import eu.darken.butler.apps.core.engine.TagFilterConfig
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterOptionsDialog(
    modifier: Modifier = Modifier,
    currentFilter: TagFilterConfig,
    availableTags: List<AppTag>,
    onDismiss: () -> Unit,
    onApply: (TagFilterConfig) -> Unit,
) {
    var includeTags by remember { mutableStateOf(currentFilter.includeTags) }
    var excludeTags by remember { mutableStateOf(currentFilter.excludeTags) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.apps_action_filter))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // Include section (AND logic)
                Text(
                    text = stringResource(R.string.apps_filter_include_label),
                    style = MaterialTheme.typography.titleSmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    availableTags.forEach { tag ->
                        val isSelected = tag in includeTags
                        TagFilterChip(
                            tag = tag,
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    includeTags = includeTags - tag
                                } else {
                                    // Remove from exclude if present, add to include
                                    excludeTags = excludeTags - tag
                                    includeTags = includeTags + tag
                                }
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Exclude section (OR logic)
                Text(
                    text = stringResource(R.string.apps_filter_exclude_label),
                    style = MaterialTheme.typography.titleSmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    availableTags.forEach { tag ->
                        val isSelected = tag in excludeTags
                        TagFilterChip(
                            tag = tag,
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    excludeTags = excludeTags - tag
                                } else {
                                    // Remove from include if present, add to exclude
                                    includeTags = includeTags - tag
                                    excludeTags = excludeTags + tag
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        onApply(TagFilterConfig())
                        onDismiss()
                    }
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_reset_action))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onApply(
                            TagFilterConfig(
                                includeTags = includeTags,
                                excludeTags = excludeTags,
                            )
                        )
                        onDismiss()
                    }
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_apply_action))
                }
            }
        },
        dismissButton = null,
    )
}

@Preview2
@Composable
private fun FilterOptionsDialogEmptyPreview() {
    PreviewWrapper {
        FilterOptionsDialog(
            currentFilter = TagFilterConfig(),
            availableTags = AppTag.standardTags,
            onDismiss = {},
            onApply = {},
        )
    }
}

@Preview2
@Composable
private fun FilterOptionsDialogWithSelectionPreview() {
    PreviewWrapper {
        FilterOptionsDialog(
            currentFilter = TagFilterConfig(
                includeTags = setOf(AppTag.System, AppTag.Disabled),
                excludeTags = setOf(AppTag.Debug),
            ),
            availableTags = AppTag.standardTags + AppTag.User(handleId = 10, label = "Work"),
            onDismiss = {},
            onApply = {},
        )
    }
}
