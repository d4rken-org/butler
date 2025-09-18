package eu.darken.butler.explorer.ui.explorer.issues

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun ErrorSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
) {
    Snackbar(
        modifier = modifier,
        action = snackbarData.visuals.actionLabel?.let { actionLabel ->
            {
                TextButton(
                    onClick = { snackbarData.performAction() }
                ) {
                    Text(
                        text = actionLabel,
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            }
        },
        dismissAction = if (snackbarData.visuals.withDismissAction) {
            {
                TextButton(
                    onClick = { snackbarData.dismiss() }
                ) {
                    Text(
                        text = "✕",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        } else null,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(text = snackbarData.visuals.message)
    }
}

@Preview2
@Composable
fun ErrorSnackbarPreview() {
    PreviewWrapper {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val snackbarHostState = remember { SnackbarHostState() }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(16.dp)
            ) { data ->
                ErrorSnackbar(snackbarData = data)
            }

            // Show a sample error snackbar
            Snackbar(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                action = {
                    TextButton(onClick = {}) {
                        Text(
                            text = stringResource(eu.darken.butler.common.R.string.general_retry_action),
                            color = MaterialTheme.colorScheme.inversePrimary,
                        )
                    }
                }
            ) {
                Text("Cannot read file: document.pdf")
            }
        }
    }
}

@Preview2
@Composable
fun ErrorSnackbarWithDismissPreview() {
    PreviewWrapper {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Snackbar(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                dismissAction = {
                    TextButton(onClick = {}) {
                        Text(
                            text = "✕",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            ) {
                Text("Permission denied: /system/app")
            }
        }
    }
}