package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material.icons.twotone.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.workspace.R as WorkspaceR

/** Shown for an encrypted archive entry until a working password is entered. */
@Composable
fun LockedEntryPlaceholder(
    modifier: Modifier = Modifier,
    issue: PathActionIssue.ArchivePasswordRequired,
    onUnlock: () -> Unit,
    onToggleChrome: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .let { base ->
                if (onToggleChrome == null) base
                else base.pointerInput(onToggleChrome) { detectTapGestures { onToggleChrome() } }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.TwoTone.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = issue.title.asComposable(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = issue.description.asComposable(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onUnlock) {
                Icon(
                    imageVector = Icons.TwoTone.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(WorkspaceR.string.workspace_issue_archive_password_unlock),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LockedEntryPlaceholderPreview() {
    LockedEntryPlaceholder(
        issue = PathActionIssue.ArchivePasswordRequired(
            container = LocalPath.build("/storage/emulated/0/Download/photos.7z"),
        ),
        onUnlock = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LockedEntryPlaceholderWrongPasswordPreview() {
    LockedEntryPlaceholder(
        issue = PathActionIssue.ArchivePasswordRequired(
            container = LocalPath.build("/storage/emulated/0/Download/photos.7z"),
            attemptFailed = true,
        ),
        onUnlock = {},
    )
}
