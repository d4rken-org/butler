package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
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
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun SearchErrorDialog(
    path: String,
    exception: Throwable,
    onCopyError: () -> Unit,
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
            Text("Search Error")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = exception.message ?: exception::class.simpleName ?: "Unknown error",
                    style = MaterialTheme.typography.bodyMedium
                )

                // Expandable stack trace
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide Details" else "Show Details")
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
                Text("Dismiss")
            }
        },
        dismissButton = {
            TextButton(onClick = onCopyError) {
                Icon(Icons.TwoTone.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Copy")
            }
        }
    )
}

@Preview2
@Composable
private fun SearchErrorDialogPreview() {
    PreviewWrapper {
        SearchErrorDialog(
            path = "/storage/emulated/0",
            exception = SecurityException("Permission denied: READ_EXTERNAL_STORAGE required"),
            onCopyError = {},
            onDismiss = {}
        )
    }
}

@Preview2
@Composable
private fun SearchErrorDialogLongMessagePreview() {
    PreviewWrapper {
        SearchErrorDialog(
            path = "/data/data/com.example.app",
            exception = java.io.IOException("I/O error occurred while trying to access the directory: Operation not permitted due to insufficient file system permissions"),
            onCopyError = {},
            onDismiss = {}
        )
    }
}
