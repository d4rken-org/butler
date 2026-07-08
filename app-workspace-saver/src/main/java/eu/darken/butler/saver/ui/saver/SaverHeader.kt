package eu.darken.butler.saver.ui.saver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant

@Composable
internal fun SaverHeader(
    modifier: Modifier = Modifier,
    callerLabel: String?,
    createdAt: Instant?,
    workspaceId: Workspace.Id,
) {
    val formattedTime = remember(createdAt) {
        createdAt?.let {
            val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            formatter.format(Date(it.toEpochMilliseconds()))
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.saver_workspace_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.saver_workspace_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (callerLabel != null && callerLabel != "?") {
                Text(
                    text = stringResource(R.string.saver_shared_from, callerLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            formattedTime?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WorkspaceButton(
            currentWorkspaceId = workspaceId,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverHeaderPreview() {
    SaverHeader(
        callerLabel = "Telegram",
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        workspaceId = Workspace.Id(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverHeaderNoCallerPreview() {
    SaverHeader(
        callerLabel = null,
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        workspaceId = Workspace.Id(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverHeaderUnknownCallerPreview() {
    SaverHeader(
        callerLabel = "?",
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        workspaceId = Workspace.Id(),
    )
}
