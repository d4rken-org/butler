package eu.darken.butler.common.error

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
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
            }
        },
        // Everything goes through the confirm slot: an error can carry up to three actions, which
        // the two-slot layout could neither order nor wrap correctly.
        dismissButton = null,
        confirmButton = {
            ErrorActionRow(
                modifier = Modifier.fillMaxWidth(),
                info = {
                    if (infoAction != null) {
                        // Deliberately does not dismiss: details are meant to be read next to the error
                        TextButton(onClick = { infoAction() }) {
                            Text(
                                localizedError.infoActionLabel?.get(context)
                                    ?: stringResource(R.string.general_show_details_action),
                            )
                        }
                    }
                },
                dismiss = {
                    if (fixAction != null) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.general_dismiss_action))
                        }
                    }
                },
                fix = {
                    if (fixAction != null) {
                        TextButton(
                            onClick = {
                                fixAction()
                                onDismiss()
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
        },
    )
}

/**
 * Action row for up to three error actions, kept in `info → dismiss → fix` reading order.
 *
 * Each action is measured on its own, so a row that does not fit wraps between actions instead of
 * being clipped — a plain `Row` inside a dialog's action slot is a single placeable and can only be
 * cut off. Order is preserved when wrapping, which keeps visual and accessibility traversal order
 * identical in both layouts. A slot that emits nothing takes no space.
 */
@Composable
private fun ErrorActionRow(
    info: @Composable () -> Unit,
    dismiss: @Composable () -> Unit,
    fix: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
) {
    Layout(
        modifier = modifier,
        contents = listOf(info, dismiss, fix),
    ) { slots, constraints ->
        val spacingPx = spacing.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val actions = slots.mapNotNull { it.firstOrNull()?.measure(childConstraints) }

        val width = constraints.maxWidth
        val lines = mutableListOf<MutableList<Placeable>>()
        actions.forEach { action ->
            val line = lines.lastOrNull()
            val lineWidth = line?.sumOf { it.width + spacingPx } ?: 0
            if (line == null || lineWidth + action.width > width) {
                lines += mutableListOf(action)
            } else {
                line += action
            }
        }

        val lineHeights = lines.map { line -> line.maxOf { it.height } }
        val height = lineHeights.sum() + spacingPx * (lines.size - 1).coerceAtLeast(0)

        // placeRelative, never place: x is measured from the layout's *start* edge, so the actions
        // mirror in a right-to-left locale instead of being pinned to the physical right.
        layout(width, height) {
            var y = 0
            lines.forEachIndexed { index, line ->
                val lineHeight = lineHeights[index]
                var x = width - (line.sumOf { it.width + spacingPx } - spacingPx)
                line.forEach { action ->
                    action.placeRelative(x = x, y = y + (lineHeight - action.height) / 2)
                    x += action.width + spacingPx
                }
                y += lineHeight + spacingPx
            }
        }
    }
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
