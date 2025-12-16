package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.WorkspaceInfoBar

@Composable
fun SearcherInfoBar(
    modifier: Modifier = Modifier,
    selectedCount: Int = 0,
    onClearSelection: () -> Unit = {},
) {
    WorkspaceInfoBar(
        modifier = modifier,
        selectedCount = selectedCount,
        onClearSelection = onClearSelection,
    )
}

@Preview2
@Composable
private fun SearcherInfoBarWithSelectionPreview() {
    PreviewWrapper {
        SearcherInfoBar(
            selectedCount = 3,
            onClearSelection = {},
        )
    }
}
