package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R

@Composable
fun SearchOptionsRow(
    caseSensitive: Boolean,
    wholeWord: Boolean,
    useRegex: Boolean,
    onToggleCaseSensitive: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onToggleRegex: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = caseSensitive,
            onClick = onToggleCaseSensitive,
            label = { Text("Aa") },
            leadingIcon = if (caseSensitive) {
                {
                    Icon(
                        imageVector = Icons.Default.TextFormat,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else null,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = Modifier.semantics {
                contentDescription = "Case sensitive search"
            }
        )
        
        FilterChip(
            selected = wholeWord,
            onClick = onToggleWholeWord,
            label = { Text("Word") },
            leadingIcon = if (wholeWord) {
                {
                    Icon(
                        imageVector = Icons.Default.FormatQuote,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else null,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = Modifier.semantics {
                contentDescription = "Whole word search"
            }
        )
        
        FilterChip(
            selected = useRegex,
            onClick = onToggleRegex,
            label = { Text(".*") },
            leadingIcon = if (useRegex) {
                {
                    Icon(
                        imageVector = Icons.Default.WrapText,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else null,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = Modifier.semantics {
                contentDescription = "Regular expression search"
            }
        )
    }
}

@Preview2
@Composable
private fun SearchOptionsRowPreview() {
    PreviewWrapper {
        SearchOptionsRow(
            caseSensitive = true,
            wholeWord = false,
            useRegex = false,
            onToggleCaseSensitive = {},
            onToggleWholeWord = {},
            onToggleRegex = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}