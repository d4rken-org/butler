package eu.darken.butler.apps.ui.details.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AppRegistration
import androidx.compose.material.icons.twotone.CellTower
import androidx.compose.material.icons.twotone.DataObject
import androidx.compose.material.icons.twotone.MiscellaneousServices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

internal fun ComponentKind.icon(): ImageVector = when (this) {
    ComponentKind.ACTIVITY -> Icons.TwoTone.AppRegistration
    ComponentKind.SERVICE -> Icons.TwoTone.MiscellaneousServices
    ComponentKind.RECEIVER -> Icons.TwoTone.CellTower
    ComponentKind.PROVIDER -> Icons.TwoTone.DataObject
}

@Composable
internal fun ComponentKind.label(): String = stringResource(
    when (this) {
        ComponentKind.ACTIVITY -> R.string.apps_components_kind_activity
        ComponentKind.SERVICE -> R.string.apps_components_kind_service
        ComponentKind.RECEIVER -> R.string.apps_components_kind_receiver
        ComponentKind.PROVIDER -> R.string.apps_components_kind_provider
    }
)

@Composable
internal fun ComponentRow(
    modifier: Modifier = Modifier,
    entry: ComponentEntry,
    query: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val highlightStyle = SpanStyle(
        background = colors.primary.copy(alpha = 0.25f),
        color = colors.primary,
        fontWeight = FontWeight.SemiBold,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.kind.icon(),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = entry.simpleName.highlightMatches(query, highlightStyle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.className.highlightMatches(query, highlightStyle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ComponentStatusChips(entry = entry)
        }
    }
}

/**
 * Status chips for a component. `UNRESOLVED` renders nothing, so the frame before the enrichment
 * pass lands looks exactly like it did before there were chips at all.
 */
@Composable
private fun ComponentStatusChips(
    modifier: Modifier = Modifier,
    entry: ComponentEntry,
) {
    val showDisabled = entry.enabledState == ComponentEnabledState.DISABLED
    if (!entry.isExported && !showDisabled) return

    Row(
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (entry.isExported) {
            ButlerChip(
                label = stringResource(R.string.apps_components_exported),
                size = ButlerChipSize.Mini,
                colors = ButlerChipDefaults.accentedColors(),
            )
        }
        if (showDisabled) {
            ButlerChip(
                label = stringResource(R.string.apps_components_disabled),
                size = ButlerChipSize.Mini,
                colors = ButlerChipDefaults.errorColors(),
            )
        }
    }
}

@Composable
internal fun ComponentGroupHeader(
    modifier: Modifier = Modifier,
    title: String,
    count: Int,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentRowExportedPreview() {
    ComponentRow(
        entry = ComponentEntry(
            kind = ComponentKind.ACTIVITY,
            packageName = "com.example.app",
            className = "com.example.app.MainActivity",
            isExported = true,
            enabledState = ComponentEnabledState.ENABLED,
        ),
        query = "",
        onClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentRowDisabledPreview() {
    ComponentRow(
        entry = ComponentEntry(
            kind = ComponentKind.RECEIVER,
            packageName = "com.example.app",
            className = "com.example.app.BootReceiver",
            isExported = false,
            enabledState = ComponentEnabledState.DISABLED,
        ),
        query = "",
        onClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentRowHighlightedPreview() {
    ComponentRow(
        entry = ComponentEntry(
            kind = ComponentKind.SERVICE,
            packageName = "com.example.app",
            className = "com.example.app.sync.SyncService",
            isExported = false,
            enabledState = ComponentEnabledState.ENABLED,
        ),
        query = "sync",
        onClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentGroupHeaderPreview() {
    ComponentGroupHeader(
        title = stringResource(R.string.apps_components_activities_label),
        count = 12,
    )
}
