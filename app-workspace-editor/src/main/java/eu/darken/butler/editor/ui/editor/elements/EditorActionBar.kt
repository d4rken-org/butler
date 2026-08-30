package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar

@Composable
fun EditorActionBar(
    modifier: Modifier = Modifier,
    actions: List<EditorActionBarItem>,
    onActionClick: (EditorActionBarItem) -> Unit,
) {
    WorkspaceActionBar(
        modifier = modifier,
        actions = actions,
        onActionClick = onActionClick,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorActionBarPreview() {
    EditorActionBar(
        actions = listOf(
            EditorActionBarItem.Cut,
            EditorActionBarItem.Copy,
            EditorActionBarItem.Paste,
            EditorActionBarItem.Delete,
        ),
        onActionClick = {},
    )
}
