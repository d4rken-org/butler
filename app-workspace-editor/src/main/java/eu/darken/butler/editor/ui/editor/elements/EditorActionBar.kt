package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.ui.editor.elements.EditorActionBarItem
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar

@Composable
fun EditorActionBar(
    modifier: Modifier = Modifier,
    actions: List<EditorActionBarItem>,
    onActionClick: (EditorActionBarItem) -> Unit,
    onActionLongClick: (EditorActionBarItem) -> Unit = {},
) {
    WorkspaceActionBar(
        modifier = modifier,
        actions = actions,
        onActionClick = { action -> onActionClick(action as EditorActionBarItem) },
        onActionLongClick = { action -> onActionLongClick(action as EditorActionBarItem) },
    )
}

@Preview2
@Composable
private fun EditorActionBarPreview() = PreviewWrapper {
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
