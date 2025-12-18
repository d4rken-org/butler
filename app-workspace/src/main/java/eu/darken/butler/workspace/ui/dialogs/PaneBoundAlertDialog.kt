package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Alert dialog that is bound to its parent pane bounds rather than appearing at window level.
 * This enables proper multi-pane support where dialogs appear over their specific workspace.
 *
 * Unlike Material's AlertDialog which uses the Dialog composable and creates a new window,
 * this dialog uses regular composables and stays within the bounds of its parent container.
 */
@Composable
fun PaneBoundAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = Modifier.zIndex(100f), // High zIndex to appear above workspace content
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .padding(horizontal = 48.dp)
                    .then(modifier),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Title
                    title?.let {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            Box(modifier = Modifier.padding(bottom = 8.dp)) {
                                it()
                            }
                        }
                    }

                    // Content text
                    text?.let {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(modifier = Modifier.padding(bottom = 8.dp)) {
                                it()
                            }
                        }
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismissButton?.let {
                            Box(modifier = Modifier.padding(end = 8.dp)) {
                                it()
                            }
                        }
                        confirmButton()
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun PaneBoundAlertDialogPreview() {
    PreviewWrapper {
        // Simulate a workspace pane with content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Background workspace content
            Text(
                text = "Workspace content underneath",
                modifier = Modifier.padding(16.dp),
            )

            // Dialog overlay
            PaneBoundAlertDialog(
                onDismissRequest = {},
                title = {
                    Text("Confirmation Dialog")
                },
                text = {
                    Text("This dialog is bound to the pane and won't appear over other workspaces in multi-pane layouts.")
                },
                confirmButton = {
                    TextButton(onClick = {}) {
                        Text("Confirm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {}) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}
