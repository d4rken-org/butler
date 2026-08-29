package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.workspace.R

/**
 * Card shell shared by every section of the operation details sheet: a clickable title row that
 * toggles [content] behind a divider.
 *
 * [title] is uppercased for display and interpolated into the expand/collapse description in the
 * case it was given, so pass it in natural case.
 */
@Composable
internal fun OperationSection(
    modifier: Modifier = Modifier,
    title: String,
    initiallyExpanded: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    accentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dividerColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    content: @Composable ColumnScope.() -> Unit,
) {
    var isExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = accentColor,
                )

                Icon(
                    imageVector = if (isExpanded) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                    contentDescription = if (isExpanded) {
                        stringResource(R.string.operations_details_section_collapse, title)
                    } else {
                        stringResource(R.string.operations_details_section_expand, title)
                    },
                    modifier = Modifier.size(20.dp),
                    tint = accentColor,
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = dividerColor,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    content()
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationSectionExpandedPreview() {
    Box(modifier = Modifier.width(360.dp)) {
        OperationSection(title = "Overview") {
            Text(text = "Section content")
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationSectionCollapsedPreview() {
    Box(modifier = Modifier.width(360.dp)) {
        OperationSection(title = "Overview", initiallyExpanded = false) {
            Text(text = "Section content")
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationSectionErrorPreview() {
    Box(modifier = Modifier.width(360.dp)) {
        OperationSection(
            title = "Error details",
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            accentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
            dividerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
        ) {
            Text(text = "Section content")
        }
    }
}
