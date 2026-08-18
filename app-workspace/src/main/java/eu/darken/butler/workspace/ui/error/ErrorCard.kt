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
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
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

            // Labels only, no leading icons: the fix label is arbitrary localized text supplied by
            // the error (`fixActionLabel`), so an icon's ~22.dp is width the longest translation
            // needs more than the user needs a wrench next to the word "Setup". The shared
            // ErrorDialog, driven by the same LocalizedError, has always been text-only.
            ErrorActionRow(
                modifier = Modifier.fillMaxWidth(),
                retryButton = onRetry?.let { retryAction ->
                    {
                        TextButton(onClick = retryAction) {
                            Text(stringResource(eu.darken.butler.common.R.string.general_retry_action))
                        }
                    }
                },
                fixButton = if (fixAction != null && fixLabel != null) {
                    {
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
                        ) {
                            Text(fixLabel)
                        }
                    }
                } else {
                    null
                },
                shareButton = {
                    OutlinedButton(onClick = onShareError) {
                        Text(stringResource(eu.darken.butler.common.R.string.general_share_error_action))
                    }
                },
            )
        }
    }
}

/**
 * Action row for [ErrorCard]: one row while every action fits across the card, a full-width stacked
 * column once they don't. The stacked branch is the reason this is a [Layout] rather than a `Row`
 * with weights — the previous weighted row split the width into equal shares regardless of label
 * length, so a long action wrapped its own text into two lines while a short one sat in whitespace.
 *
 * Nothing here can be decided at design time. The fix action's label is arbitrary localized text
 * carried on the error ("Open Setup", "Google Play", "Clear saved session"), translations arrive
 * from Crowdin rather than the repo, and font scale rescales all of it, so the fit has to be
 * measured. A dp breakpoint would also miss the narrow-pane case, where the card is laid out well
 * below the window width.
 *
 * ### Why intrinsics rather than a trial measure
 * A `Measurable` may only be measured once per pass, so the branch has to be chosen *before*
 * measuring: [androidx.compose.ui.layout.IntrinsicMeasurable.maxIntrinsicWidth] reports the width a
 * button wants with its label unwrapped, without consuming the measure.
 *
 * ### Placement order is focus order
 * One-dimensional focus traversal sorts siblings by `LayoutNode.placeOrder`, not composition order.
 * Both branches place retry, then fix, then share — the order the row reads on screen, and the order
 * the stack reads top to bottom. Don't reshuffle the `placeRelative` calls.
 *
 * A slot that emits nothing is treated as absent, so a card with no retry and no fix action lays out
 * its share action alone, exactly as the weighted row's `fill = false` branch used to. Unlike
 * `DialogActionRow` there is no debug-time check that a slot emitted at most one measurable: that row
 * takes its actions from callers across the codebase, whereas all three slots here are supplied a few
 * lines above by [ErrorCard] itself.
 *
 * Stacking is a floor, not a guarantee against wrapping: an action whose label cannot fit even the
 * full card width — a very narrow pane at a very large font scale — still wraps inside its button.
 *
 * @param retryButton leading action, absent when the error has nothing to retry.
 * @param fixButton the error's own remedy, absent when it offers none.
 * @param shareButton always present; the diagnostic action, kept at the end.
 */
@Composable
private fun ErrorActionRow(
    modifier: Modifier = Modifier,
    retryButton: (@Composable () -> Unit)? = null,
    fixButton: (@Composable () -> Unit)? = null,
    shareButton: @Composable () -> Unit,
    mainAxisSpacing: Dp = 8.dp,
    crossAxisSpacing: Dp = 8.dp,
) {
    Layout(
        modifier = modifier,
        contents = listOf(
            { if (retryButton != null) retryButton() },
            { if (fixButton != null) fixButton() },
            shareButton,
        ),
    ) { contents, constraints ->
        val actions = contents.mapNotNull { it.firstOrNull() }
        if (actions.isEmpty()) {
            return@Layout layout(if (constraints.hasBoundedWidth) constraints.maxWidth else 0, 0) {}
        }

        val mainAxisSpacingPx = mainAxisSpacing.roundToPx()
        val crossAxisSpacingPx = crossAxisSpacing.roundToPx()

        val rowWidth = actions.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } +
            mainAxisSpacingPx * (actions.size - 1)
        // An unbounded maxWidth is a width *query*, not a width: taking it literally would report an
        // infinite layout and divide an infinite slack into the gaps. Answering it with the row's own
        // intrinsic width is also what makes the default intrinsic-width approximation correct, since
        // Compose derives that by running this policy with the width unbounded.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else rowWidth
        val singleRow = rowWidth <= width

        // placeRelative, never place: x is measured from the layout's *start* edge, so the actions
        // mirror to the left in a right-to-left locale instead of being pinned to the physical
        // right. Arabic ships as a supported locale, so this is a real configuration.
        if (singleRow) {
            val placeables = actions.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            val height = placeables.maxOf { it.height }
            // Leftover space goes into the gaps rather than into the buttons: the row keeps spanning
            // the card the way the weighted one did, without stretching a short label's touch target
            // across a third of it. No lower bound needed — reaching this branch means the intrinsic
            // total *including* mainAxisSpacing fit, so the slack cannot divide below it.
            val slack = width - placeables.sumOf { it.width }
            val gap = if (placeables.size > 1) slack / (placeables.size - 1) else 0

            layout(width, height) {
                var x = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(x = x, y = (height - placeable.height) / 2)
                    x += placeable.width + gap
                }
            }
        } else {
            val stacked = constraints.copy(minWidth = width, maxWidth = width, minHeight = 0)
            val placeables = actions.map { it.measure(stacked) }
            val height = placeables.sumOf { it.height } + crossAxisSpacingPx * (placeables.size - 1)

            layout(width, height) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(x = 0, y = y)
                    y += placeable.height + crossAxisSpacingPx
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

/** All three actions, the shape that used to wrap its labels on a Pixel 8. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ErrorCardWithFixActionPreview() {
    ErrorCard(
        title = "Can't show this file",
        error = PreviewFixableError(fixLabel = "Open Setup"),
        onShareError = {},
        onRetry = {},
    )
}

/** A fix label long enough to force the stacked branch, as German and large font scales do. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ErrorCardStackedActionsPreview() {
    ErrorCard(
        title = "Can't show this file",
        error = PreviewFixableError(fixLabel = "Einrichtung öffnen"),
        onShareError = {},
        onRetry = {},
    )
}

private class PreviewFixableError(
    private val fixLabel: String,
) : RuntimeException("Permission denied"), HasLocalizedError {
    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "Permission required".toCaString(),
        description = ("Cannot access \"qa-open.png\" at \"/storage/emulated/0/Download\" due to " +
            "insufficient permissions.").toCaString(),
        fixActionLabel = fixLabel.toCaString(),
        fixAction = {},
    )
}

private val TAG = logTag("Workspace", "ErrorCard")
