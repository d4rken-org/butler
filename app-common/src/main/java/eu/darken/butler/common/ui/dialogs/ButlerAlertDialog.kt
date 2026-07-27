package eu.darken.butler.common.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.window.DialogProperties
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

object ButlerAlertDialogDefaults {
    const val SURFACE_TEST_TAG = "common.dialog.window.surface"
}

/**
 * Alert dialog in a platform window, built on the shared [ButlerAlertDialogContent].
 *
 * Mirrors Material's own `AlertDialogImpl` — same window, same defaults — and adds the [neutralButton]
 * slot, which Material's `AlertDialog` has no equivalent for. Everything about the way the dialog
 * looks lives in the shell, so a window dialog and a pane-bound one cannot drift apart.
 *
 * @param neutralButton action placed at the *start* of the action row, away from confirm/dismiss.
 */
@Composable
fun ButlerAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    neutralButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    // modifier goes here rather than onto the shell: BasicAlertDialog wraps the content in a box
    // with sizeIn(280dp, 560dp) and propagateMinConstraints, so the shell's Surface receives the
    // Material minimum width as a real constraint and the host contributes no width of its own.
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties,
    ) {
        // Required, not defensive. BasicAlertDialog constrains width only and leaves the height
        // unbounded, so without an explicit bound the shell's verticalScroll would depend on the
        // platform dialog root happening to pass down a finite height. propagateMinConstraints keeps
        // the minimum width from above from being swallowed here.
        BoxWithConstraints(propagateMinConstraints = true) {
            ButlerAlertDialogContent(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .testTag(ButlerAlertDialogDefaults.SURFACE_TEST_TAG),
                confirmButton = confirmButton,
                dismissButton = dismissButton,
                neutralButton = neutralButton,
                icon = icon,
                title = title,
                text = text,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerAlertDialogPreview() {
    ButlerAlertDialogPreviewFrame {
        ButlerAlertDialog(
            onDismissRequest = {},
            title = { Text("Confirmation Dialog") },
            text = { Text("This dialog opens in its own window, over everything else.") },
            confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerAlertDialogWithNeutralActionPreview() {
    ButlerAlertDialogPreviewFrame {
        ButlerAlertDialog(
            onDismissRequest = {},
            title = { Text("Rename") },
            text = { Text("Enter a new name for this tab.") },
            confirmButton = { TextButton(onClick = {}) { Text("Rename") } },
            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
            neutralButton = { TextButton(onClick = {}) { Text("Clear") } },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerAlertDialogWithIconPreview() {
    ButlerAlertDialogPreviewFrame {
        ButlerAlertDialog(
            onDismissRequest = {},
            icon = { Text("!") },
            title = { Text("Delete 3 items?") },
            text = { Text("The selected items will be moved to the trash.") },
            confirmButton = { TextButton(onClick = {}) { Text("Delete") } },
            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ButlerAlertDialogPreviewFrame(content: @Composable () -> Unit) {
    PreviewWrapper {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}
