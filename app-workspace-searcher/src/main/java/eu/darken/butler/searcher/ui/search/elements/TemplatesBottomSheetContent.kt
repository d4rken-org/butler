package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchTemplate

@Composable
fun TemplatesBottomSheetContent(
    modifier: Modifier = Modifier,
    onTemplateClick: (SearchTemplate) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
    ) {
        // Title
        Text(
            text = stringResource(R.string.searcher_templates_action),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )

        // Template list
        SearchTemplate.builtIn.forEach { template ->
            TemplateRow(
                template = template,
                onClick = { onTemplateClick(template) },
            )
        }
    }
}

@Composable
private fun TemplateRow(
    template: SearchTemplate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = template.icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = template.name.asComposable(),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = template.description.asComposable(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview2
@Composable
private fun TemplatesBottomSheetContentPreview() {
    PreviewWrapper {
        TemplatesBottomSheetContent(
            onTemplateClick = {},
        )
    }
}

@Preview2
@Composable
private fun TemplateRowPreview() {
    PreviewWrapper {
        TemplateRow(
            template = SearchTemplate.LargeFiles,
            onClick = {},
        )
    }
}
