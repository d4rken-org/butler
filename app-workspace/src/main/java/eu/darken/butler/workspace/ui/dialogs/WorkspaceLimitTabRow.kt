package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatSmartTime
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceLimitCandidate
import eu.darken.butler.workspace.core.icon
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * One open tab in the free-tier limit dialog: what it is, where it points, how long it has been
 * around - enough to decide which tab to give up without opening it first.
 *
 * A blocked candidate ([WorkspaceLimitCandidate.blocker] set) renders dimmed, without a checkbox and
 * without a click target, and trades its metadata line for the reason. Listing it anyway is the
 * point: a list that hid it would not add up to the tab count in the message above.
 */
@Composable
fun WorkspaceLimitTabRow(
    candidate: WorkspaceLimitCandidate,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
) {
    val context = LocalContext.current
    val enabled = candidate.isClosable && selectable
    val contentColor = if (candidate.isClosable) {
        LocalContentColor.current
    } else {
        LocalContentColor.current.copy(alpha = 0.38f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .let {
                if (enabled) {
                    it.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
                } else {
                    it
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            modifier = Modifier.size(24.dp),
            imageVector = candidate.type.icon,
            contentDescription = null,
            tint = contentColor,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.title.get(context),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.secondaryLine(),
                style = MaterialTheme.typography.bodySmall,
                color = if (candidate.isClosable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectable) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = candidate.isClosable,
            )
        }
    }
}

/**
 * What the row says under the title: the blocker when there is one, otherwise where the tab points
 * and how old it is. Falls back to the tab's age alone when it has no subtitle to show.
 */
@Composable
private fun WorkspaceLimitCandidate.secondaryLine(): String {
    blocker?.let { return stringResource(it.labelRes) }
    val age = stringResource(R.string.workspace_limit_tab_opened, formatSmartTime(openedAt))
    val where = subtitle?.get(LocalContext.current)?.takeIf { it.isNotBlank() } ?: return age
    return "$where · $age"
}

private val WorkspaceLimitCandidate.Blocker.labelRes: Int
    get() = when (this) {
        WorkspaceLimitCandidate.Blocker.UNSAVED_CHANGES -> R.string.workspace_limit_blocker_unsaved
        WorkspaceLimitCandidate.Blocker.BUSY -> R.string.workspace_limit_blocker_busy
        WorkspaceLimitCandidate.Blocker.NEEDS_ATTENTION -> R.string.workspace_limit_blocker_attention
        WorkspaceLimitCandidate.Blocker.LOADING -> R.string.workspace_limit_blocker_loading
        WorkspaceLimitCandidate.Blocker.HAS_MODAL -> R.string.workspace_limit_blocker_modal
    }

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceLimitTabRowPreview() {
    PreviewWrapper {
        Column {
            WorkspaceLimitTabRow(
                candidate = WorkspaceLimitCandidate(
                    id = Workspace.Id(),
                    type = Workspace.Type.EXPLORER,
                    title = "Downloads".toCaString(),
                    subtitle = "/sdcard/Download".toCaString(),
                    openedAt = Clock.System.now() - 2.hours,
                ),
                selected = false,
                onToggle = {},
            )
            WorkspaceLimitTabRow(
                candidate = WorkspaceLimitCandidate(
                    id = Workspace.Id(),
                    type = Workspace.Type.SEARCHER,
                    title = "*.log".toCaString(),
                    subtitle = "/sdcard".toCaString(),
                    openedAt = Clock.System.now() - 5.minutes,
                ),
                selected = true,
                onToggle = {},
            )
            WorkspaceLimitTabRow(
                candidate = WorkspaceLimitCandidate(
                    id = Workspace.Id(),
                    type = Workspace.Type.EDITOR,
                    title = "notes.txt".toCaString(),
                    subtitle = "/sdcard/Documents".toCaString(),
                    openedAt = Clock.System.now() - 30.minutes,
                    blocker = WorkspaceLimitCandidate.Blocker.UNSAVED_CHANGES,
                ),
                selected = false,
                onToggle = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceLimitTabRowReadOnlyPreview() {
    PreviewWrapper {
        WorkspaceLimitTabRow(
            candidate = WorkspaceLimitCandidate(
                id = Workspace.Id(),
                type = Workspace.Type.APPS,
                title = "Apps".toCaString(),
                subtitle = null,
                openedAt = Clock.System.now() - 3.hours,
                blocker = WorkspaceLimitCandidate.Blocker.BUSY,
            ),
            selected = false,
            onToggle = {},
            selectable = false,
        )
    }
}
