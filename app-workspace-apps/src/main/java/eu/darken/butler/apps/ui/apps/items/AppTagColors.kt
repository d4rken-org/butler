package eu.darken.butler.apps.ui.apps.items

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.apps.core.engine.labelRes

/**
 * Color pair for tag chips.
 */
data class TagColors(
    val container: Color,
    val content: Color,
)

/**
 * Returns the display colors for this tag.
 * This keeps color logic in the UI layer, separate from the domain model.
 */
@Composable
fun AppTag.colors(): TagColors = when (this) {
    is AppTag.Disabled -> TagColors(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )
    is AppTag.System -> TagColors(
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    is AppTag.Sideloaded -> TagColors(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    is AppTag.UpdatedSystem -> TagColors(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    is AppTag.Debug -> TagColors(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    is AppTag.SplitApk -> TagColors(
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    is AppTag.User -> TagColors(
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    is AppTag.Enabled -> TagColors(
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    is AppTag.UserApp -> TagColors(
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/**
 * Returns the display label for this tag.
 */
@Composable
fun AppTag.label(): String = when (this) {
    is AppTag.User -> label ?: stringResource(labelRes, handleId)
    else -> stringResource(labelRes)
}
