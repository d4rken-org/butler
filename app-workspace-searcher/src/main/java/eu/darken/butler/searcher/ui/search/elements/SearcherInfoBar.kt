package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.searcher.R
import eu.darken.butler.workspace.ui.InfoChip
import eu.darken.butler.workspace.ui.WorkspaceInfoBar

@Composable
fun SearcherInfoBar(
    modifier: Modifier = Modifier,
    resultsCount: Int = 0,
    totalSize: Long = 0L,
    selectedCount: Int = 0,
    onClearSelection: () -> Unit = {},
) {
    WorkspaceInfoBar(
        modifier = modifier,
        selectedCount = selectedCount,
        onClearSelection = onClearSelection,
        leadingContent = {
            if (selectedCount == 0 && resultsCount > 0) {
                InfoChip(
                    icon = Icons.TwoTone.Search,
                    label = pluralStringResource(R.plurals.searcher_infobar_results_count, resultsCount, resultsCount),
                )
            }
        },
        trailingContent = {
            if (selectedCount == 0 && totalSize > 0) {
                Spacer(modifier = Modifier.weight(1f))
                InfoChip(
                    icon = Icons.TwoTone.Storage,
                    label = formatFileSize(totalSize),
                )
            }
        },
    )
}

@Preview2
@Composable
private fun SearcherInfoBarWithResultsPreview() {
    PreviewWrapper {
        SearcherInfoBar(
            resultsCount = 42,
            totalSize = 1024L * 1024L * 512L,
            selectedCount = 0,
            onClearSelection = {},
        )
    }
}

@Preview2
@Composable
private fun SearcherInfoBarWithSelectionPreview() {
    PreviewWrapper {
        SearcherInfoBar(
            resultsCount = 42,
            totalSize = 1024L * 1024L * 512L,
            selectedCount = 3,
            onClearSelection = {},
        )
    }
}
