package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Clear
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * The bare input: no background, no padding, no icons. Hosts that already draw a surface around it
 * inline this instead of [AppsSearchBar], so the field doesn't end up as a card inside a card with
 * both paddings stacking.
 */
@Composable
fun AppsSearchField(
    modifier: Modifier = Modifier,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    hint: String = stringResource(R.string.apps_search_hint),
    autoFocus: Boolean = false,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }

    // Keyed on the flag rather than Unit, so a false -> true flip in a surviving composition (the
    // toolbar swapping its title for the search input) still opens the keyboard.
    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = colors.onSurface
        ),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboardController?.hide()
        }),
        singleLine = true,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Box {
                if (query.text.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                innerTextField()
            }
        }
    )
}

/** [AppsSearchField] on its own surface, for hosts that need a standalone search box. */
@Composable
fun AppsSearchBar(
    modifier: Modifier = Modifier,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    hint: String = stringResource(R.string.apps_search_hint),
    autoFocus: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading icon - search icon
            Box(modifier = Modifier.padding(end = 8.dp)) {
                Icon(
                    imageVector = Icons.TwoTone.Search,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = colors.primary,
                )
            }

            AppsSearchField(
                modifier = Modifier.weight(1f),
                query = query,
                onQueryChange = onQueryChange,
                hint = hint,
                autoFocus = autoFocus,
            )

            // Trailing icon - clear button when query is not empty
            if (query.text.isNotEmpty()) {
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    Icon(
                        imageVector = Icons.TwoTone.Clear,
                        contentDescription = stringResource(eu.darken.butler.common.R.string.general_clear_action),
                        modifier = Modifier
                            .clickable { onQueryChange(TextFieldValue("")) }
                            .size(24.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsSearchBarPreview() {
    AppsSearchBar(
        query = TextFieldValue("Gmail"),
        onQueryChange = {},
        modifier = Modifier.padding(16.dp)
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsSearchBarEmptyPreview() {
    AppsSearchBar(
        query = TextFieldValue(""),
        onQueryChange = {},
        modifier = Modifier.padding(16.dp)
    )
}
