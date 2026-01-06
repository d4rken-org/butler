package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.ui.editor.EditorAction
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar

@Composable
fun EditorActionBar(
    modifier: Modifier = Modifier,
    actions: List<EditorAction>,
    onActionClick: (EditorAction) -> Unit,
    onActionLongClick: (EditorAction) -> Unit = {},
) {
    WorkspaceActionBar(
        modifier = modifier,
        actions = actions,
        onActionClick = { action -> onActionClick(action as EditorAction) },
        onActionLongClick = { action -> onActionLongClick(action as EditorAction) },
    )
}

@Preview2
@Composable
private fun EditorActionBarPreview() = PreviewWrapper {
    EditorActionBar(
        actions = listOf(
            EditorAction.Cut,
            EditorAction.Copy,
            EditorAction.Paste,
            EditorAction.Delete,
        ),
        onActionClick = {},
    )
}
