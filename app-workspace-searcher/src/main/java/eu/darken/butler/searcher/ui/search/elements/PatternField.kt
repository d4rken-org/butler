package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.Clear
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R

@Composable
fun PatternField(
    modifier: Modifier = Modifier,
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    caseSensitive: Boolean,
    wholeWord: Boolean,
    useRegex: Boolean,
    isSearching: Boolean = false,
    onToggleCaseSensitive: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onToggleRegex: () -> Unit,
    extraMenuItems: (@Composable () -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    var menuExpanded by remember { mutableStateOf(false) }

    // Local TextFieldValue state to manage cursor position independently
    var textFieldValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }

    // Sync external text changes (e.g., from history restore)
    LaunchedEffect(text) {
        if (text != textFieldValue.text) {
            textFieldValue = TextFieldValue(text = text, selection = TextRange(text.length))
        }
    }

    // Check if any options are enabled
    val hasActiveOptions = caseSensitive || wholeWord || useRegex

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Leading icon
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )

        // Text field
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                onTextChange(newValue.text)
            },
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colors.onSurface,
            ),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboardController?.hide()
                onSearch()
            }),
            singleLine = true,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Box {
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    innerTextField()
                }
            },
        )

        // Refresh button (hidden while searching)
        Icon(
            imageVector = Icons.TwoTone.Refresh,
            contentDescription = stringResource(eu.darken.butler.common.R.string.general_refresh_action),
            modifier = Modifier
                .clickable { onSearch() }
                .size(20.dp)
                .visible(!isSearching),
            tint = colors.onSurfaceVariant,
        )

        // Clear button
        if (textFieldValue.text.isNotEmpty()) {
            Icon(
                imageVector = Icons.TwoTone.Clear,
                contentDescription = stringResource(eu.darken.butler.common.R.string.general_clear_action),
                modifier = Modifier
                    .clickable {
                        textFieldValue = TextFieldValue("")
                        onTextChange("")
                    }
                    .size(20.dp),
                tint = colors.onSurfaceVariant,
            )
        }

        // Options overflow menu
        Box {
            Icon(
                imageVector = Icons.TwoTone.MoreVert,
                contentDescription = stringResource(R.string.searcher_options_action),
                modifier = Modifier
                    .clickable { menuExpanded = true }
                    .size(20.dp),
                tint = if (hasActiveOptions) colors.primary else colors.onSurfaceVariant,
            )

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                // Extra menu items (e.g., "Search content" toggle)
                extraMenuItems?.invoke()

                DropdownMenuItem(
                    text = { Text(stringResource(R.string.searcher_option_case_sensitive_label)) },
                    onClick = onToggleCaseSensitive,
                    leadingIcon = {
                        Checkbox(
                            checked = caseSensitive,
                            onCheckedChange = null,
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.searcher_option_whole_word_label)) },
                    onClick = onToggleWholeWord,
                    leadingIcon = {
                        Checkbox(
                            checked = wholeWord,
                            onCheckedChange = null,
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.searcher_option_regex_label)) },
                    onClick = onToggleRegex,
                    leadingIcon = {
                        Checkbox(
                            checked = useRegex,
                            onCheckedChange = null,
                        )
                    },
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PatternFieldFilenamePreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PatternField(
            text = "*.kt",
            onTextChange = {},
            onSearch = {},
            placeholder = "Filename pattern…",
            leadingIcon = Icons.AutoMirrored.TwoTone.InsertDriveFile,
            caseSensitive = false,
            wholeWord = false,
            useRegex = true,
            onToggleCaseSensitive = {},
            onToggleWholeWord = {},
            onToggleRegex = {},
        )
        PatternField(
            text = "fun main",
            onTextChange = {},
            onSearch = {},
            placeholder = "Content pattern…",
            leadingIcon = Icons.TwoTone.Description,
            caseSensitive = true,
            wholeWord = true,
            useRegex = false,
            onToggleCaseSensitive = {},
            onToggleWholeWord = {},
            onToggleRegex = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PatternFieldEmptyPreview() {
    PatternField(
        text = "",
        onTextChange = {},
        onSearch = {},
        placeholder = "Filename pattern…",
        leadingIcon = Icons.AutoMirrored.TwoTone.InsertDriveFile,
        caseSensitive = false,
        wholeWord = false,
        useRegex = false,
        onToggleCaseSensitive = {},
        onToggleWholeWord = {},
        onToggleRegex = {},
        modifier = Modifier.padding(16.dp),
    )
}
