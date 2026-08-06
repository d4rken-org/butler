package eu.darken.butler.common.error

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.dialogs.AdaptiveAlertDialog

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

    val infoAction = localizedError.infoAction
    val fixAction = localizedError.fixAction

    // Keyed on the throwable, not the LocalizedError: the latter is rebuilt (with fresh action
    // lambdas, so never equal) on every recomposition, which would wipe the message immediately.
    var actionError by remember(throwable) { mutableStateOf<CaString?>(null) }

    // errorMessage is per-dispatch, NOT read from localizedError: this function serves both the fix
    // and the info button, and fixActionErrorMessage describes only the fix action's failure. Each
    // call site passes its own copy (or none), so no button can ever surface another one's message.
    fun dispatch(
        action: () -> Unit,
        dismissAfter: Boolean,
        errorMessage: CaString? = null,
    ) {
        // Error actions are arbitrary third-party code (intent launches, navigation): a throw here
        // would crash the UI thread from inside a click handler, and skipping onDismiss() would
        // leave the dialog latched on the current error with no way out.
        try {
            action()
        } catch (e: Exception) {
            log(TAG, ERROR) { "Error action failed: ${e.asLog()}" }
            // A dispatch that ships its own failure copy keeps the dialog open and shows it inline
            // (no length cap, unlike a Toast). Never latched: the dismiss button stays available.
            errorMessage?.let {
                actionError = it
                return
            }
        }
        if (dismissAfter) onDismiss()
    }

    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = localizedError.label.get(context),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                SelectionContainer {
                    Text(
                        text = localizedError.description.get(context),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                actionError?.let {
                    SelectionContainer {
                        Text(
                            text = it.get(context),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }
        },
        // "Show details" is the neutral action: it belongs to the error rather than to resolving it,
        // and it is the one that drops to its own line first when the row cannot hold all three.
        neutralButton = if (infoAction != null) {
            {
                // Deliberately does not dismiss: details are meant to be read next to the error.
                // No errorMessage either: the info action has no failure copy of its own, and it
                // must never borrow the fix action's.
                TextButton(
                    onClick = {
                        dispatch(
                            action = infoAction,
                            dismissAfter = false,
                        )
                    },
                ) {
                    Text(
                        localizedError.infoActionLabel?.get(context)
                            ?: stringResource(R.string.general_show_details_action),
                    )
                }
            }
        } else {
            null
        },
        // Without a fix there is nothing to decline, so the acknowledging action stands alone
        dismissButton = if (fixAction != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.general_dismiss_action))
                }
            }
        } else {
            null
        },
        confirmButton = {
            if (fixAction != null) {
                TextButton(
                    onClick = {
                        dispatch(
                            action = fixAction,
                            dismissAfter = true,
                            errorMessage = localizedError.fixActionErrorMessage,
                        )
                    },
                ) {
                    Text(
                        localizedError.fixActionLabel?.get(context)
                            ?: stringResource(android.R.string.ok),
                    )
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
fun ErrorDialogPreview() {
    ErrorDialog(
        throwable = RuntimeException("Sample error message for preview"),
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ErrorDialogWithInfoAndFixPreview() {
    ErrorDialog(
        throwable = PreviewLocalizedError(withInfo = true, withFix = true),
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ErrorDialogWithFixPreview() {
    ErrorDialog(
        throwable = PreviewLocalizedError(withInfo = false, withFix = true),
        onDismiss = {},
    )
}

private class PreviewLocalizedError(
    private val withInfo: Boolean,
    private val withFix: Boolean,
) : RuntimeException("Storage permission is missing"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "Permission required".toCaString(),
        description = "Butler needs access to this location before it can list its contents.".toCaString(),
        infoActionLabel = if (withInfo) "Learn more".toCaString() else null,
        infoAction = if (withInfo) ({ }) else null,
        fixActionLabel = if (withFix) "Grant access".toCaString() else null,
        fixAction = if (withFix) ({ }) else null,
    )
}

private val TAG = logTag("Error", "Dialog")
