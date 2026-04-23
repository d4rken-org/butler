package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.WrapText
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.FormatQuote
import androidx.compose.material.icons.twotone.TextFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.ToggleFilterChip
import eu.darken.butler.searcher.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchOptionsRow(
    caseSensitive: Boolean,
    wholeWord: Boolean,
    useRegex: Boolean,
    searchContent: Boolean,
    onToggleCaseSensitive: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onToggleRegex: () -> Unit,
    onToggleSearchContent: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ToggleFilterChip(
            selected = caseSensitive,
            onClick = onToggleCaseSensitive,
            labelRes = R.string.searcher_option_case_sensitive_label,
            iconVector = Icons.TwoTone.TextFormat,
            contentDescriptionRes = R.string.searcher_option_case_sensitive_desc,
        )

        ToggleFilterChip(
            selected = wholeWord,
            onClick = onToggleWholeWord,
            labelRes = R.string.searcher_option_whole_word_label,
            iconVector = Icons.TwoTone.FormatQuote,
            contentDescriptionRes = R.string.searcher_option_whole_word_desc,
        )

        ToggleFilterChip(
            selected = useRegex,
            onClick = onToggleRegex,
            labelRes = R.string.searcher_option_regex_label,
            iconVector = Icons.AutoMirrored.TwoTone.WrapText,
            contentDescriptionRes = R.string.searcher_option_regex_desc,
        )

        ToggleFilterChip(
            selected = searchContent,
            onClick = onToggleSearchContent,
            labelRes = R.string.searcher_option_search_content_label,
            iconVector = Icons.TwoTone.Description,
            contentDescriptionRes = R.string.searcher_option_search_content_desc,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchOptionsRowPreview() {
    SearchOptionsRow(
        caseSensitive = true,
        wholeWord = false,
        useRegex = false,
        searchContent = false,
        onToggleCaseSensitive = {},
        onToggleWholeWord = {},
        onToggleRegex = {},
        onToggleSearchContent = {},
        modifier = Modifier.padding(8.dp)
    )
}