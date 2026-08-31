package eu.darken.butler.workspace.ui.states

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode.*
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.manager.FakeWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.common.R as CommonR

/**
 * "The thing you opened is gone" - for a workspace that cannot open its target because the path no
 * longer exists, as opposed to failing in a way worth reporting.
 *
 * Wording comes from the throwable's own [localized] error, so each workspace keeps its own
 * phrasing (a missing file, a missing file with a recoverable save backup, a deleted image) while
 * sharing one presentation. Nothing here is workspace-specific, and nothing here type-matches:
 * deciding that a failure *is* a vanished path belongs to the caller, which is the only layer that
 * can see its own exception types.
 */
@Composable
fun PathGoneBody(
    modifier: Modifier = Modifier,
    error: Throwable,
) {
    val context = LocalContext.current
    val localized = remember(error) { error.localized(c = context) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ButlerMascot(
            modifier = Modifier.size(96.dp),
            variant = Static.Sad(),
        )

        Text(
            text = localized.label.get(context),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = localized.description.get(context),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Full-pane variant of [PathGoneBody], shaped like [WorkspaceErrorContent] so a workspace whose
 * lifecycle failed on a vanished path can swap one overlay for the other.
 *
 * No retry: the overwhelmingly common cause is a deletion, which retrying cannot cure.
 */
@Composable
fun PathGoneContent(
    modifier: Modifier = Modifier,
    design: WorkspaceDesign = WorkspaceDesign(),
    error: Throwable,
    onShareError: () -> Unit,
    onCloseWorkspace: (() -> Unit)? = null,
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
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            PathGoneBody(
                modifier = Modifier.fillMaxWidth(),
                error = error,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (onCloseWorkspace != null) {
                    TextButton(onClick = onCloseWorkspace) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.workspace_close_tab_action))
                        }
                    }
                }

                OutlinedButton(onClick = onShareError) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(CommonR.string.general_share_error_action))
                    }
                }
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
private fun PathGoneContentPreview() {
    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider()
    ) {
        PathGoneContent(
            error = PathNotFoundException(LocalPath.build("/storage/emulated/0/Documents/notes.txt")),
            onShareError = {},
            onCloseWorkspace = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PathGoneBodyPreview() {
    PathGoneBody(
        error = PathNotFoundException(LocalPath.build("/storage/emulated/0/Pictures/holiday.jpg")),
    )
}
