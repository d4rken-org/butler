package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.ui.operations.OperationDisplay

@Composable
internal fun OperationErrorSection(
    state: OperationDisplay.State.Failed,
) {
    OperationSection(
        title = stringResource(R.string.operations_details_error),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        accentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
        dividerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
    ) {
        Text(
            text = state.summary.asComposable(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
