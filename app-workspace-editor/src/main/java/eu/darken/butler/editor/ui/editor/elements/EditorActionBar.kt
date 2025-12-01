package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.ui.editor.EditorAction
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.scroll.BottomBarScrollState

@Composable
fun EditorActionBar(
    modifier: Modifier = Modifier,
    actions: List<EditorAction>,
    scrollState: BottomBarScrollState,
    onActionClick: (EditorAction) -> Unit,
) {
    WorkspaceActionBar(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .graphicsLayer {
                // Immediate snap behavior: fully visible or fully hidden
                alpha = if (scrollState.collapsedFraction > 0.1f) 0f else 1f
                translationY = if (scrollState.collapsedFraction > 0.1f) 64.dp.toPx() else 0f
            },
        actions = actions,
        onActionClick = { action -> onActionClick(action as EditorAction) },
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
        scrollState = BottomBarScrollState(),
        onActionClick = {},
    )
}
