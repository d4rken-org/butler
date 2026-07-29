package eu.darken.butler.apps.ui.details.components

import android.content.pm.ActivityInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Launch
import androidx.compose.material.icons.twotone.Block
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.core.details.components.ComponentToggleState
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.rememberClipboardCopy
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

/**
 * Pane-scoped details sheet for a single manifest component.
 *
 * [entry] is nullable and the sheet is *always composed*, with `visible` driven by `entry != null`:
 * a conditional `entry?.let { … }` in the caller would unmount the sheet the instant the selection
 * clears, skipping the slide-out and handing the page's back handler back a frame early.
 * [PaneScopedBottomSheet] already owns the pane layer, the back handler, the scrim, drag-dismiss and
 * the horizontal pane insets — none of those belong here.
 */
@Composable
fun ComponentDetailsSheet(
    modifier: Modifier = Modifier,
    entry: ComponentEntry?,
    onDismiss: () -> Unit,
    onLaunch: (() -> Unit)? = null,
    toggleState: ComponentToggleState = ComponentToggleState.UNSUPPORTED,
    onSetEnabled: (Boolean) -> Unit = {},
    onSetupRequested: () -> Unit = {},
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    // Keeps the content alive through the exit transition after the selection cleared.
    val shown = remember { mutableStateOf<ComponentEntry?>(null) }
    if (entry != null) {
        shown.value = entry
    }

    PaneScopedBottomSheet(
        modifier = modifier,
        visible = entry != null,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
    ) {
        shown.value?.let { current ->
            ComponentDetailsContent(
                entry = current,
                onDismiss = onDismiss,
                onLaunch = onLaunch,
                toggleState = toggleState,
                onSetEnabled = onSetEnabled,
                onSetupRequested = onSetupRequested,
            )
        }
    }
}

@Composable
private fun ComponentDetailsContent(
    modifier: Modifier = Modifier,
    entry: ComponentEntry,
    onDismiss: () -> Unit,
    onLaunch: (() -> Unit)?,
    toggleState: ComponentToggleState = ComponentToggleState.UNSUPPORTED,
    onSetEnabled: (Boolean) -> Unit = {},
    onSetupRequested: () -> Unit = {},
) {
    val copy = rememberClipboardCopy()

    Column(modifier = modifier.fillMaxWidth()) {
        ComponentHeroCard(
            entry = entry,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ComponentInfoRow(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.apps_components_field_type),
                    value = entry.kind.label(),
                )
                // Omitted while unresolved: the sheet must not claim a state it has not resolved.
                // Type keeps its weighted column either way, so the layout doesn't jump.
                if (entry.enabledState != ComponentEnabledState.UNRESOLVED) {
                    ComponentInfoRow(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.apps_components_state_label),
                        value = if (entry.enabledState == ComponentEnabledState.ENABLED) {
                            stringResource(R.string.apps_components_state_enabled)
                        } else {
                            stringResource(R.string.apps_components_state_disabled)
                        },
                    )
                }
            }
            ComponentInfoRow(
                label = stringResource(R.string.apps_components_field_package),
                value = entry.packageName,
            )
            entry.permission?.let {
                ComponentInfoRow(label = stringResource(R.string.apps_components_field_permission), value = it)
            }
            entry.writePermission?.let {
                ComponentInfoRow(label = stringResource(R.string.apps_components_field_write_permission), value = it)
            }
            entry.authority?.let {
                ComponentInfoRow(label = stringResource(R.string.apps_components_field_authority), value = it)
            }
            entry.processName?.let {
                ComponentInfoRow(label = stringResource(R.string.apps_components_field_process), value = it)
            }
            entry.launchMode?.let {
                ComponentInfoRow(
                    label = stringResource(R.string.apps_components_field_launch_mode),
                    value = launchModeKeyword(it),
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        // Hidden entirely when there is nothing to toggle, or while the current state is unknown —
        // without a resolved state there is no direction to offer.
        if (toggleState != ComponentToggleState.UNSUPPORTED &&
            entry.enabledState != ComponentEnabledState.UNRESOLVED
        ) {
            val currentlyEnabled = entry.enabledState == ComponentEnabledState.ENABLED
            ComponentActionRow(
                icon = if (currentlyEnabled) Icons.TwoTone.Block else Icons.TwoTone.CheckCircle,
                title = if (currentlyEnabled) {
                    stringResource(R.string.apps_components_action_disable)
                } else {
                    stringResource(R.string.apps_components_action_enable)
                },
                subtitle = when (toggleState) {
                    ComponentToggleState.NEEDS_SETUP -> stringResource(R.string.apps_components_action_requires_elevated)
                    else -> null
                },
                // Dimmed, not disabled: the row stays clickable and routes into setup.
                dimmed = toggleState == ComponentToggleState.NEEDS_SETUP,
                onClick = {
                    onDismiss()
                    when (toggleState) {
                        ComponentToggleState.NEEDS_SETUP -> onSetupRequested()
                        else -> onSetEnabled(!currentlyEnabled)
                    }
                },
            )
        }
        if (onLaunch != null) {
            ComponentActionRow(
                icon = Icons.AutoMirrored.TwoTone.Launch,
                title = stringResource(R.string.apps_components_launch_action),
                // Matches the previous inline affordance: starting a non-exported or disabled
                // activity cannot succeed without elevated access.
                enabled = entry.isExported && entry.enabledState != ComponentEnabledState.DISABLED,
                onClick = {
                    onDismiss()
                    onLaunch()
                },
            )
        }
        ComponentActionRow(
            icon = Icons.TwoTone.ContentCopy,
            title = stringResource(R.string.apps_components_copy_name_action),
            onClick = { copy(entry.className) },
        )
        ComponentActionRow(
            icon = Icons.TwoTone.ContentCopy,
            title = stringResource(R.string.apps_components_copy_package_action),
            onClick = { copy(entry.packageName) },
        )
    }
}

@Composable
private fun ComponentHeroCard(
    modifier: Modifier = Modifier,
    entry: ComponentEntry,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = entry.kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.simpleName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entry.className,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ButlerChip(
                        label = entry.kind.label(),
                        size = ButlerChipSize.Compact,
                        colors = ButlerChipDefaults.colors(),
                    )
                    if (entry.isExported) {
                        ButlerChip(
                            label = stringResource(R.string.apps_components_exported),
                            size = ButlerChipSize.Compact,
                            colors = ButlerChipDefaults.accentedColors(),
                        )
                    }
                    if (entry.enabledState == ComponentEnabledState.DISABLED) {
                        ButlerChip(
                            label = stringResource(R.string.apps_components_disabled),
                            size = ButlerChipSize.Compact,
                            colors = ButlerChipDefaults.errorColors(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentInfoRow(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            letterSpacing = 0.5.sp,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * @param enabled `false` greys the row *and* makes it inert.
 * @param dimmed greys the row while leaving it clickable — for an action that is currently
 *        unavailable but whose tap leads somewhere useful (e.g. the setup screen).
 */
@Composable
private fun ComponentActionRow(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    enabled: Boolean = true,
    subtitle: String? = null,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled && !dimmed) 1f else 0.38f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
            modifier = Modifier.size(24.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Manifest tokens, not prose — deliberately literals rather than string resources. */
private fun launchModeKeyword(launchMode: Int): String = when (launchMode) {
    ActivityInfo.LAUNCH_MULTIPLE -> "standard"
    ActivityInfo.LAUNCH_SINGLE_TOP -> "singleTop"
    ActivityInfo.LAUNCH_SINGLE_TASK -> "singleTask"
    ActivityInfo.LAUNCH_SINGLE_INSTANCE -> "singleInstance"
    ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK -> "singleInstancePerTask"
    else -> launchMode.toString()
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentDetailsSheetActivityPreview() {
    ComponentDetailsSheet(
        entry = ComponentEntry(
            kind = ComponentKind.ACTIVITY,
            packageName = "com.example.app",
            className = "com.example.app.MainActivity",
            isExported = true,
            enabledState = ComponentEnabledState.ENABLED,
            launchMode = ActivityInfo.LAUNCH_SINGLE_TASK,
            processName = "com.example.app:ui",
        ),
        onDismiss = {},
        onLaunch = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentDetailsSheetProviderPreview() {
    ComponentDetailsSheet(
        entry = ComponentEntry(
            kind = ComponentKind.PROVIDER,
            packageName = "com.example.app",
            className = "com.example.app.data.FileProvider",
            isExported = false,
            enabledState = ComponentEnabledState.DISABLED,
            permission = "android.permission.READ_EXTERNAL_STORAGE",
            writePermission = "android.permission.WRITE_EXTERNAL_STORAGE",
            authority = "com.example.app.files",
        ),
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentDetailsSheetUnresolvedPreview() {
    ComponentDetailsSheet(
        entry = ComponentEntry(
            kind = ComponentKind.SERVICE,
            packageName = "com.example.app",
            className = "com.example.app.sync.SyncService",
            isExported = false,
        ),
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentDetailsSheetToggleAvailableEnabledPreview() {
    ComponentDetailsSheet(
        entry = ComponentEntry(
            kind = ComponentKind.RECEIVER,
            packageName = "com.example.app",
            className = "com.example.app.BootReceiver",
            isExported = true,
            enabledState = ComponentEnabledState.ENABLED,
        ),
        onDismiss = {},
        toggleState = ComponentToggleState.AVAILABLE,
        onSetEnabled = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentDetailsSheetToggleAvailableDisabledPreview() {
    ComponentDetailsSheet(
        entry = ComponentEntry(
            kind = ComponentKind.RECEIVER,
            packageName = "com.example.app",
            className = "com.example.app.BootReceiver",
            isExported = true,
            enabledState = ComponentEnabledState.DISABLED,
        ),
        onDismiss = {},
        toggleState = ComponentToggleState.AVAILABLE,
        onSetEnabled = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentDetailsSheetToggleNeedsSetupPreview() {
    ComponentDetailsSheet(
        entry = ComponentEntry(
            kind = ComponentKind.RECEIVER,
            packageName = "com.example.app",
            className = "com.example.app.BootReceiver",
            isExported = true,
            enabledState = ComponentEnabledState.ENABLED,
        ),
        onDismiss = {},
        toggleState = ComponentToggleState.NEEDS_SETUP,
        onSetupRequested = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentDetailsSheetToggleUnsupportedPreview() {
    ComponentDetailsSheet(
        entry = ComponentEntry(
            kind = ComponentKind.RECEIVER,
            packageName = "com.example.app",
            className = "com.example.app.BootReceiver",
            isExported = true,
            enabledState = ComponentEnabledState.ENABLED,
        ),
        onDismiss = {},
        toggleState = ComponentToggleState.UNSUPPORTED,
    )
}
