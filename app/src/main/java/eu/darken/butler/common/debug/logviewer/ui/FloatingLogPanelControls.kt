package eu.darken.butler.common.debug.logviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.OpenInFull
import androidx.compose.material.icons.twotone.Pause
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.R as CommonR

@Composable
internal fun OverflowMenu(
    state: FloatingLogPanelViewModel.State,
    onToggleSearch: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenLevelDialog: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.TwoTone.MoreVert,
                contentDescription = stringResource(R.string.debug_logview_more_action),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.debug_logview_search_action)) },
                leadingIcon = { Icon(Icons.TwoTone.Search, contentDescription = null) },
                onClick = {
                    expanded = false
                    onToggleSearch()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (state.isPaused) R.string.debug_logview_resume_action else R.string.debug_logview_pause_action
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.isPaused) Icons.TwoTone.PlayArrow else Icons.TwoTone.Pause,
                        contentDescription = null,
                        tint = if (state.isPaused) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                },
                onClick = {
                    expanded = false
                    onTogglePause()
                },
            )
            // Level selection opens a dialog so the menu doesn't grow with every priority.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.debug_logview_level_action)) },
                leadingIcon = { Icon(Icons.TwoTone.Tune, contentDescription = null) },
                trailingIcon = {
                    Text(
                        text = state.displayPriority.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                onClick = {
                    expanded = false
                    onOpenLevelDialog()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.debug_logview_clear_action)) },
                leadingIcon = { Icon(Icons.TwoTone.DeleteSweep, contentDescription = null) },
                onClick = {
                    expanded = false
                    onClear()
                },
            )

            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.debug_logview_copy_action)) },
                leadingIcon = { Icon(Icons.TwoTone.ContentCopy, contentDescription = null) },
                onClick = {
                    expanded = false
                    onCopy()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(CommonR.string.general_share_action)) },
                leadingIcon = { Icon(Icons.TwoTone.Share, contentDescription = null) },
                onClick = {
                    expanded = false
                    onShare()
                },
            )

            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.debug_logview_close_action)) },
                leadingIcon = { Icon(Icons.TwoTone.Close, contentDescription = null) },
                onClick = {
                    expanded = false
                    onClose()
                },
            )
        }
    }
}

@Composable
internal fun LogLevelDialog(
    current: Logging.Priority,
    onSelect: (Logging.Priority) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.debug_logview_level_action)) },
        text = {
            Column {
                LEVELS.forEach { level ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(level)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = level == current,
                            onClick = {
                                onSelect(level)
                                onDismiss()
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(level.displayName())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_close_action))
            }
        },
    )
}

@Composable
internal fun ResizeGrip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.TwoTone.OpenInFull,
            contentDescription = stringResource(R.string.debug_logview_resize_action),
            modifier = Modifier.size(14.dp),
            tint = LocalContentColor.current.copy(alpha = 0.6f),
        )
    }
}

@Composable
internal fun CompactSearchField(
    modifier: Modifier = Modifier,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.TwoTone.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Controlled by hoisted local state (synchronous), never by the async VM query — the
            // latter round-trip is what scrambles the cursor.
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.debug_logview_search_action),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            val isEmpty = value.text.isEmpty()
            IconButton(
                onClick = { if (isEmpty) onClose() else onClear() },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Close,
                    contentDescription = stringResource(
                        if (isEmpty) R.string.debug_logview_search_close_action else R.string.debug_logview_search_clear_action,
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun Logging.Priority.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

internal val LEVELS = listOf(
    Logging.Priority.VERBOSE,
    Logging.Priority.DEBUG,
    Logging.Priority.INFO,
    Logging.Priority.WARN,
    Logging.Priority.ERROR,
)

@Preview2
@Composable
private fun LogLevelDialogPreview() {
    PreviewWrapper {
        LogLevelDialog(
            current = Logging.Priority.DEBUG,
            onSelect = {},
            onDismiss = {},
        )
    }
}

@Preview2
@Composable
private fun CompactSearchFieldPreview() {
    PreviewWrapper {
        CompactSearchField(
            value = TextFieldValue("gateway"),
            onValueChange = {},
            onClear = {},
            onClose = {},
        )
    }
}

@Preview2
@Composable
private fun ResizeGripPreview() {
    PreviewWrapper {
        ResizeGrip()
    }
}

@Preview2
@Composable
private fun OverflowMenuPreview() {
    PreviewWrapper {
        OverflowMenu(
            state = FloatingLogPanelViewModel.State(isPaused = true),
            onToggleSearch = {},
            onTogglePause = {},
            onOpenLevelDialog = {},
            onClear = {},
            onCopy = {},
            onShare = {},
            onClose = {},
        )
    }
}
