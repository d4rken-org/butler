package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceFeedback
import eu.darken.butler.common.R as CommonR

/**
 * Names the tab that was just closed and offers to bring it back. Butler has no
 * [androidx.compose.material3.SnackbarHost] infrastructure, so this follows the existing
 * `FavoritesFeedbackBar` chrome convention: a card in a floating bar stack.
 *
 * The name is resolved here rather than by the stash: a user-set name overrides the automatic one,
 * which is a [eu.darken.butler.common.ca.CaString] that only a composition can turn into text.
 */
@Composable
fun WorkspaceClosedFeedbackBar(
    modifier: Modifier = Modifier,
    feedback: ClosedWorkspaceFeedback,
    onUndo: () -> Unit,
) {
    val context = LocalContext.current
    val label = feedback.customTitle ?: feedback.automaticTitle.get(context)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.workspace_closed_undo_message, label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onUndo) {
                Text(
                    text = stringResource(CommonR.string.general_undo_action),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceClosedFeedbackBarPreview() {
    PreviewWrapper {
        WorkspaceClosedFeedbackBar(
            feedback = ClosedWorkspaceFeedback(
                closeToken = 1L,
                customTitle = null,
                automaticTitle = "Downloads".toCaString(),
            ),
            onUndo = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceClosedFeedbackBarNamedPreview() {
    PreviewWrapper {
        WorkspaceClosedFeedbackBar(
            feedback = ClosedWorkspaceFeedback(
                closeToken = 1L,
                customTitle = "Holiday pictures",
                automaticTitle = "Downloads".toCaString(),
            ),
            onUndo = {},
        )
    }
}
