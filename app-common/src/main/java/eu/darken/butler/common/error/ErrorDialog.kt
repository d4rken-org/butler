package eu.darken.butler.common.error

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.navigation.NavigationController

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ErrorDialogEntryPoint {
    fun permissionFixResolver(): PermissionFixResolver
}

@Composable
fun ErrorDialog(
    throwable: Throwable,
    onDismiss: () -> Unit,
    navController: NavigationController? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val resolver = remember(context) {
        runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, ErrorDialogEntryPoint::class.java)
                .permissionFixResolver()
        }.getOrNull()
    }

    val errorContext = LocalizedErrorContext(
        activity = activity,
        navController = navController,
        permissionFixResolver = resolver,
    )
    val localizedError = throwable.localized(context, errorContext)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = localizedError.label.get(context),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column {
                SelectionContainer {
                    Text(
                        text = localizedError.description.get(context),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                localizedError.infoAction?.let { action ->
                    TextButton(onClick = { action() }) {
                        Text(
                            localizedError.infoActionLabel?.get(context)
                                ?: stringResource(R.string.general_show_details_action)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                localizedError.fixAction?.let { action ->
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.general_dismiss_action))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            action()
                            onDismiss()
                        }
                    ) {
                        Text(
                            localizedError.fixActionLabel?.get(context)
                                ?: stringResource(android.R.string.ok)
                        )
                    }
                }
                    ?: TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.ok))
                    }
            }
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
fun ErrorDialogPreview() {
    ErrorDialog(
        throwable = RuntimeException("Sample error message for preview"),
        onDismiss = {}
    )
}
