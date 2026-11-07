package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.hidden.HideFeedback

/**
 * Confirms a hide. While the tab shows hidden entries the hidden ones stay on screen, dimmed, so the
 * bar says so and offers to stop showing hidden entries in this tab.
 */
@Composable
fun HiddenFeedbackBar(
    modifier: Modifier = Modifier,
    feedback: HideFeedback,
    onStopShowingHidden: () -> Unit,
) {
    val context = LocalContext.current
    val message = when {
        feedback.count > 1 -> pluralStringResource(
            R.plurals.explorer_hidden_feedback_multiple_message,
            feedback.count,
            feedback.count,
        )
        else -> stringResource(R.string.explorer_hidden_feedback_message, feedback.displayName.get(context))
    }
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
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (feedback.showHiddenOn) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.explorer_hidden_feedback_dimmed_hint,
                            feedback.count,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (feedback.showHiddenOn) {
                TextButton(onClick = onStopShowingHidden) {
                    Text(
                        text = stringResource(R.string.explorer_hidden_feedback_dont_show_action),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HiddenFeedbackBarPreview() {
    PreviewWrapper {
        HiddenFeedbackBar(
            feedback = HideFeedback(
                id = 1L,
                displayName = "notes.txt".toCaString(),
                count = 1,
                showHiddenOn = false,
            ),
            onStopShowingHidden = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HiddenFeedbackBarShowingHiddenPreview() {
    PreviewWrapper {
        HiddenFeedbackBar(
            feedback = HideFeedback(
                id = 1L,
                displayName = "notes.txt".toCaString(),
                count = 3,
                showHiddenOn = true,
            ),
            onStopShowingHidden = {},
        )
    }
}
