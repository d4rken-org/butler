package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerIcon
import eu.darken.butler.common.compose.ButlerIconVariant
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.ui.dialogs.ButlerAlertDialog
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceLimitCandidate
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import eu.darken.butler.common.R as CommonR

object WorkspaceLimitDialogDefaults {
    const val TAB_LIST_TEST_TAG = "workspace.limit.dialog.tabs"
    const val CONFIRM_TEST_TAG = "workspace.limit.dialog.confirm"
}

/**
 * Stays a window-level dialog on purpose: it has to sit above the tab manager overlay AND above a
 * full-screen modal workspace, which is also where closing a tab from here matters most - inside
 * such a modal there is no tab manager to reach, so this list is the only way forward.
 *
 * Renders in one of three shapes, driven by what the repo could offer:
 * - **selection** ([canRecover] with something closable in [candidates]): the tabs are pickable and
 *   closing them completes the create that was blocked.
 * - **read-only** ([candidates] non-empty, but not recoverable): a restore can push the tab count so
 *   far past [limit] that closing everything closable still would not free a slot. The tabs are
 *   still listed - seeing what holds the slots beats being told a number.
 * - **notice** (no [candidates]): nothing to replay, so this is the bare message it always was.
 *
 * Nothing is pre-selected. Closing a tab cannot be undone, so the confirm action stays disabled
 * until the user has picked [minToClose] of them.
 *
 * Holds the selection itself rather than hoisting it: it is scratch state that dies with the dialog,
 * and the caller has nothing to do with it until the confirm action fires. Callers that can re-post
 * a fresh dialog should wrap this in `key(dialogId)` so a new one does not inherit stale ticks.
 *
 * @param minToClose how many tabs must go before the blocked create fits; above 1 only after a
 *        session restore pushed the count past the limit.
 */
@Composable
fun WorkspaceLimitDialog(
    limit: Int,
    onDismiss: () -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    candidates: List<WorkspaceLimitCandidate> = emptyList(),
    canRecover: Boolean = false,
    minToClose: Int = 1,
    onCloseSelected: (Set<Workspace.Id>) -> Unit = {},
) {
    var selected by rememberSaveable(
        stateSaver = listSaver<Set<Workspace.Id>, Workspace.Id>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(emptySet()) }

    val isSelectable = canRecover && candidates.any { it.isClosable }
    val hasEnough = selected.size >= minToClose

    ButlerAlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        icon = {
            ButlerIcon(
                modifier = Modifier.size(48.dp),
                variant = ButlerIconVariant.SAD,
            )
        },
        title = {
            Text(text = stringResource(R.string.workspace_limit_reached_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = if (isSelectable) {
                        stringResource(R.string.workspace_limit_pick_tabs_message, limit)
                    } else {
                        stringResource(R.string.workspace_limit_reached_message, limit)
                    },
                )

                if (candidates.isNotEmpty()) {
                    // Plain Column, never lazy: the dialog shell already wraps this slot in a
                    // verticalScroll, and a lazy list inside one gets an infinite height constraint.
                    Surface(
                        modifier = Modifier.testTag(WorkspaceLimitDialogDefaults.TAB_LIST_TEST_TAG),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Column {
                            candidates.forEachIndexed { index, candidate ->
                                if (index > 0) HorizontalDivider()
                                WorkspaceLimitTabRow(
                                    candidate = candidate,
                                    selected = candidate.id in selected,
                                    selectable = isSelectable,
                                    onToggle = {
                                        selected = if (candidate.id in selected) {
                                            selected - candidate.id
                                        } else {
                                            selected + candidate.id
                                        }
                                    },
                                )
                            }
                        }
                    }

                    when {
                        !isSelectable -> Text(
                            text = stringResource(R.string.workspace_limit_no_slot_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        !hasEnough -> Text(
                            text = pluralStringResource(
                                R.plurals.workspace_limit_selection_hint,
                                minToClose,
                                minToClose,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isSelectable) UpgradeOffer(onUpgrade = onUpgrade)
            }
        },
        confirmButton = {
            if (isSelectable) {
                // Before anything is picked the button names the minimum instead of counting zero:
                // "Close 0 tabs" reads like a broken action rather than one waiting on the user.
                val labelCount = selected.size.coerceAtLeast(minToClose)
                TextButton(
                    modifier = Modifier.testTag(WorkspaceLimitDialogDefaults.CONFIRM_TEST_TAG),
                    onClick = { onCloseSelected(selected) },
                    enabled = hasEnough,
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.workspace_limit_close_selected_action,
                            labelCount,
                            labelCount,
                        )
                    )
                }
            } else {
                TextButton(
                    modifier = Modifier.testTag(WorkspaceLimitDialogDefaults.CONFIRM_TEST_TAG),
                    onClick = onUpgrade,
                ) {
                    Text(text = stringResource(CommonR.string.general_upgrade_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CommonR.string.general_dismiss_action))
            }
        },
    )
}

/**
 * Keeps Pro visible without letting it take the primary action: once closing tabs is a real way out,
 * upgrading is an offer rather than the only door.
 */
@Composable
private fun UpgradeOffer(
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = Icons.TwoTone.WorkspacePremium,
                contentDescription = null,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.workspace_limit_pro_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.workspace_limit_pro_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onUpgrade) {
                Text(text = stringResource(CommonR.string.general_upgrade_action))
            }
        }
    }
}

private fun previewCandidates() = listOf(
    WorkspaceLimitCandidate(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Downloads".toCaString(),
        subtitle = "/sdcard/Download".toCaString(),
        openedAt = Clock.System.now() - 2.hours,
    ),
    WorkspaceLimitCandidate(
        id = Workspace.Id(),
        type = Workspace.Type.SEARCHER,
        title = "*.log".toCaString(),
        subtitle = "/sdcard".toCaString(),
        openedAt = Clock.System.now() - 5.minutes,
    ),
    WorkspaceLimitCandidate(
        id = Workspace.Id(),
        type = Workspace.Type.EDITOR,
        title = "notes.txt".toCaString(),
        subtitle = "/sdcard/Documents".toCaString(),
        openedAt = Clock.System.now() - 3.days,
        blocker = WorkspaceLimitCandidate.Blocker.UNSAVED_CHANGES,
    ),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceLimitDialogSelectionPreview() {
    WorkspaceLimitDialog(
        limit = 5,
        onDismiss = {},
        onUpgrade = {},
        candidates = previewCandidates(),
        canRecover = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceLimitDialogReadOnlyPreview() {
    WorkspaceLimitDialog(
        limit = 5,
        onDismiss = {},
        onUpgrade = {},
        candidates = previewCandidates().map { it.copy(blocker = WorkspaceLimitCandidate.Blocker.BUSY) },
        canRecover = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceLimitDialogNoticePreview() {
    WorkspaceLimitDialog(
        limit = 5,
        onDismiss = {},
        onUpgrade = {},
    )
}
