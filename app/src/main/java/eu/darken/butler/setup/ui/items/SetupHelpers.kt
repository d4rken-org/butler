package eu.darken.butler.setup.ui.items

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Inventory
import androidx.compose.material.icons.twotone.Notifications
import androidx.compose.material.icons.twotone.Security
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import eu.darken.butler.R
import eu.darken.butler.setup.core.SetupModule

fun getSetupIcon(type: SetupModule.Type): ImageVector = when (type) {
    SetupModule.Type.ROOT -> Icons.TwoTone.Security
    SetupModule.Type.SHIZUKU -> Icons.TwoTone.Security
    SetupModule.Type.NOTIFICATION -> Icons.TwoTone.Notifications
    SetupModule.Type.USAGE_STATS -> Icons.TwoTone.Settings
    SetupModule.Type.STORAGE -> Icons.TwoTone.Storage
    SetupModule.Type.INVENTORY -> Icons.TwoTone.Inventory
}

@Composable
fun getSetupDescription(type: SetupModule.Type): String {
    return when (type) {
        SetupModule.Type.ROOT -> stringResource(R.string.setup_root_description)
        SetupModule.Type.SHIZUKU -> stringResource(R.string.setup_shizuku_description)
        SetupModule.Type.NOTIFICATION -> stringResource(R.string.setup_notification_description)
        SetupModule.Type.USAGE_STATS -> stringResource(R.string.setup_usagestats_description)
        SetupModule.Type.STORAGE -> stringResource(R.string.setup_storage_description)
        SetupModule.Type.INVENTORY -> stringResource(R.string.setup_inventory_description)
    }
}

@Composable
fun getStatusMessage(state: SetupModule.State, isRequired: Boolean): String {
    return when (state) {
        is SetupModule.State.Loading -> stringResource(R.string.setup_status_checking)
        is SetupModule.State.Current -> {
            when {
                state.isComplete -> stringResource(R.string.setup_status_completed)
                isRequired -> stringResource(R.string.setup_status_required)
                else -> stringResource(R.string.setup_status_optional)
            }
        }
    }
}

@Composable
fun getStatusColor(state: SetupModule.State, isRequired: Boolean): Color {
    return when (state) {
        is SetupModule.State.Loading -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        is SetupModule.State.Current -> {
            when {
                state.isComplete -> MaterialTheme.colorScheme.primary
                isRequired -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            }
        }
    }
}