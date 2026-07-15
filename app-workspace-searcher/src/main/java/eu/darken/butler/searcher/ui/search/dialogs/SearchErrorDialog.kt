package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R
import java.io.IOException

@Composable
fun SearchErrorDialog(
    path: String,
    exception: Throwable,
    onShareError: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.TwoTone.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(stringResource(R.string.searcher_search_error))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = exception.message?.takeIf { it.isNotBlank() }
                        ?: exception::class.simpleName
                        ?: stringResource(eu.darken.butler.common.R.string.general_error_unknown_label),
                    style = MaterialTheme.typography.bodyMedium
                )

                // Expandable stack trace
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(
                        stringResource(
                            if (showDetails) {
                                eu.darken.butler.common.R.string.general_hide_details_action
                            } else {
                                eu.darken.butler.common.R.string.general_show_details_action
                            }
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (showDetails) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                        contentDescription = null
                    )
                }

                if (showDetails) {
                    SelectionContainer {
                        Text(
                            text = exception.stackTraceToString(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(eu.darken.butler.common.R.string.general_dismiss_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onShareError) {
                Icon(Icons.TwoTone.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(eu.darken.butler.common.R.string.general_share_error_action))
            }
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchErrorDialogPreview() {
    SearchErrorDialog(
        path = "/storage/emulated/0",
        exception = SecurityException("Permission denied: READ_EXTERNAL_STORAGE required"),
        onShareError = {},
        onDismiss = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchErrorDialogLongMessagePreview() {
    SearchErrorDialog(
        path = "/data/data/com.example.app",
        exception = IOException("I/O error occurred while trying to access the directory: Operation not permitted due to insufficient file system permissions"),
        onShareError = {},
        onDismiss = {}
    )
}
