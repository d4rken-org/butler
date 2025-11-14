package eu.darken.butler.searcher.ui.search.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.WrapText
import androidx.compose.material.icons.twotone.FormatQuote
import androidx.compose.material.icons.twotone.TextFormat
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
        val caseSensitiveDesc = stringResource(R.string.searcher_option_case_sensitive_desc)
        FilterChip(
            selected = caseSensitive,
            onClick = onToggleCaseSensitive,
            label = { Text(stringResource(R.string.searcher_option_case_sensitive_label)) },
            leadingIcon = if (caseSensitive) {
                {
                    Icon(
                        imageVector = Icons.TwoTone.TextFormat,
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
                contentDescription = caseSensitiveDesc
            }
        )

        val wholeWordDesc = stringResource(R.string.searcher_option_whole_word_desc)
        FilterChip(
            selected = wholeWord,
            onClick = onToggleWholeWord,
            label = { Text(stringResource(R.string.searcher_option_whole_word_label)) },
            leadingIcon = if (wholeWord) {
                {
                    Icon(
                        imageVector = Icons.TwoTone.FormatQuote,
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
                contentDescription = wholeWordDesc
            }
        )

        val regexDesc = stringResource(R.string.searcher_option_regex_desc)
        FilterChip(
            selected = useRegex,
            onClick = onToggleRegex,
            label = { Text(stringResource(R.string.searcher_option_regex_label)) },
            leadingIcon = if (useRegex) {
                {
                    Icon(
                        imageVector = Icons.AutoMirrored.TwoTone.WrapText,
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
                contentDescription = regexDesc
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
            modifier = Modifier.padding(8.dp)
        )
    }
}