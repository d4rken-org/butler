package eu.darken.butler.workspace.ui.states

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.manager.FakeWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import java.io.IOException
import eu.darken.butler.common.R as CommonR

/**
 * Placeholder for a paused tab: it holds its arguments but has no live instance.
 * [title]/[subtitle] identify the tab from its persisted arguments; a blank or missing title falls
 * back to the workspace type. [error] is set when the last resume attempt failed; the action then
 * offers a retry.
 */
@Composable
fun WorkspacePausedContent(
    modifier: Modifier = Modifier,
    design: WorkspaceDesign = WorkspaceDesign(),
    type: Workspace.Type,
    title: CaString? = null,
    subtitle: CaString? = null,
    error: Throwable? = null,
    onResume: () -> Unit,
    currentWorkspaceId: Workspace.Id? = null,
) {
    val paneInsets = design.paneInsets()
    val statusBarInset = paneInsets.top
    val navBarInset = paneInsets.bottom

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = statusBarInset + 32.dp, bottom = navBarInset + 32.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = type.icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            val resolvedTitle = title?.asComposable()?.takeIf { it.isNotBlank() }
                ?: type.label.asComposable()
            Text(
                text = resolvedTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )

            val resolvedSubtitle = subtitle?.asComposable()
            if (!resolvedSubtitle.isNullOrBlank()) {
                Text(
                    text = resolvedSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Text(
                text = stringResource(
                    if (error != null) R.string.workspace_paused_error_body else R.string.workspace_paused_body
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (error != null) {
                Text(
                    text = error.message ?: error.javaClass.simpleName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(
                onClick = onResume,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(
                    text = stringResource(
                        if (error != null) {
                            CommonR.string.general_retry_action
                        } else {
                            R.string.workspace_paused_resume_action
                        }
                    )
                )
            }
        }

        if (design.isSingle && LocalWorkspaceButtonProvider.current != null) {
            WorkspaceButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = statusBarInset + 24.dp, end = 24.dp),
                currentWorkspaceId = currentWorkspaceId,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePausedContentPreview() {
    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider()
    ) {
        WorkspacePausedContent(
            type = Workspace.Type.EXPLORER,
            onResume = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePausedContentTitlePreview() {
    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider()
    ) {
        WorkspacePausedContent(
            type = Workspace.Type.EXPLORER,
            title = "/storage/emulated/0/Download".toCaString(),
            onResume = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePausedContentSubtitlePreview() {
    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider()
    ) {
        WorkspacePausedContent(
            type = Workspace.Type.SEARCHER,
            title = "*.pdf".toCaString(),
            subtitle = "Downloads, Photos +3".toCaString(),
            onResume = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePausedContentErrorPreview() {
    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider()
    ) {
        WorkspacePausedContent(
            type = Workspace.Type.EDITOR,
            title = "config.json".toCaString(),
            subtitle = "/storage/emulated/0/config.json".toCaString(),
            error = IOException("Failed to resume tab: Permission denied"),
            onResume = {},
        )
    }
}
