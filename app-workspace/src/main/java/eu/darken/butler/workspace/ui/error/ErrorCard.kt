package eu.darken.butler.workspace.ui.error

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Build
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.error.PermissionFixResolver
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.navigation.LocalNavigationController
import eu.darken.butler.workspace.R
import java.io.IOException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ErrorCardEntryPoint {
    fun permissionFixResolver(): PermissionFixResolver
}

@Composable
fun ErrorCard(
    modifier: Modifier = Modifier,
    title: String,
    error: Throwable,
    onShareError: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    var showTechnicalDetails by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val navController = LocalNavigationController.current
    val resolver = remember(context) {
        runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, ErrorCardEntryPoint::class.java)
                .permissionFixResolver()
        }.getOrNull()
    }
    val localized = remember(error, navController, resolver) {
        error.localized(
            c = context,
            errorContext = LocalizedErrorContext(
                navController = navController,
                permissionFixResolver = resolver,
            ),
        )
    }
    val bodyText = localized.description.get(context)
    val fixLabel = localized.fixActionLabel?.get(context)
    val fixAction = localized.fixAction

    // Keyed on the throwable, not the LocalizedError: the latter is rebuilt (with fresh action
    // lambdas, so never equal) on every recomposition, which would wipe the message immediately.
    var actionError by remember(error) { mutableStateOf<CaString?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Title with error icon and optional dismiss button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                onDismiss?.let { dismissAction ->
                    IconButton(
                        onClick = dismissAction,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_dismiss_action),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Error message — uses HasLocalizedError when available for user-friendly text
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // A failed fix action that ships its own copy shows it here, in full: a Toast caps at
            // 2 lines and clipped this kind of message.
            actionError?.let {
                Text(
                    text = it.get(context),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Technical details section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTechnicalDetails = !showTechnicalDetails }
                            .padding(start = 8.dp, end = 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(
                                if (showTechnicalDetails) {
                                    R.string.workspace_error_hide_details_action
                                } else {
                                    R.string.workspace_error_show_details_action
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = { showTechnicalDetails = !showTechnicalDetails },
                        ) {
                            Icon(
                                imageVector = if (showTechnicalDetails) {
                                    Icons.TwoTone.ExpandLess
                                } else {
                                    Icons.TwoTone.ExpandMore
                                },
                                contentDescription = null,
                            )
                        }
                    }

                    AnimatedVisibility(visible = showTechnicalDetails) {
                        Column {
                            HorizontalDivider()
                            SelectionContainer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                            ) {
                                Text(
                                    text = error.stackTraceToString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onRetry?.let { retryAction ->
                    TextButton(
                        onClick = retryAction,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(eu.darken.butler.common.R.string.general_retry_action))
                        }
                    }
                }

                if (fixAction != null && fixLabel != null) {
                    TextButton(
                        // Error actions are arbitrary third-party code (intent launches, navigation):
                        // a throw here would crash the UI thread from inside a click handler. The card
                        // is inline and stays where it is, so there is nothing to dismiss.
                        onClick = {
                            try {
                                fixAction()
                                actionError = null
                            } catch (e: Exception) {
                                log(TAG, ERROR) { "Error action failed: ${e.asLog()}" }
                                // Per-dispatch, not read off the error: fixActionErrorMessage
                                // describes only this action's failure. Without one the card
                                // behaves exactly as before — log and stay put.
                                actionError = localized.fixActionErrorMessage
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Build,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(fixLabel)
                        }
                    }
                }

                OutlinedButton(
                    onClick = onShareError,
                    modifier = if (onRetry != null || fixAction != null) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.weight(1f, fill = false)
                    },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(eu.darken.butler.common.R.string.general_share_error_action))
                    }
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ErrorCardWithRetryPreview() {
    ErrorCard(
        title = "Navigation Failed",
        error = IOException("Failed to read directory: Permission denied"),
        onShareError = {},
        onRetry = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ErrorCardNoRetryPreview() {
    ErrorCard(
        title = "Search Error",
        error = RuntimeException("Unexpected error occurred while searching"),
        onShareError = {},
        onDismiss = null,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ErrorCardMinimalPreview() {
    ErrorCard(
        title = "Error",
        error = NullPointerException("Path lookup returned null"),
        onShareError = {},
    )
}

private val TAG = logTag("Workspace", "ErrorCard")
