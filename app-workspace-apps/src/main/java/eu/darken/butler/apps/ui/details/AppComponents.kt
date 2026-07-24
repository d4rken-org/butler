package eu.darken.butler.apps.ui.details

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
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
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ComponentsData(
    val activities: List<ActivityInfo> = emptyList(),
    val services: List<ServiceInfo> = emptyList(),
    val receivers: List<ActivityInfo> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
) {
    val total: Int get() = activities.size + services.size + receivers.size + providers.size
}

sealed interface ComponentsUiState {
    data object Loading : ComponentsUiState
    data class Ready(val data: ComponentsData) : ComponentsUiState
    data object Error : ComponentsUiState
}

/**
 * Loads component data asynchronously off the main thread, keyed by package + version so an
 * app update re-runs the query rather than serving a stale cache.
 *
 * Limitation: queries the calling user with the default component-matching policy, so
 * work-profile-only installs and disabled components are not reflected here.
 */
@Composable
internal fun rememberComponentsUiState(app: AppInfo): ComponentsUiState {
    val context = LocalContext.current
    val state by produceState<ComponentsUiState>(
        initialValue = ComponentsUiState.Loading,
        app.packageName,
        app.versionCode,
    ) {
        // Reset to Loading so a key change (app update) doesn't keep showing stale counts.
        value = ComponentsUiState.Loading
        value = withContext(Dispatchers.IO) {
            try {
                ComponentsUiState.Ready(loadComponentsData(context, app.packageName))
            } catch (e: Exception) {
                log("AppComponents", WARN) { "Failed to load components for ${app.packageName}: ${e.asLog()}" }
                ComponentsUiState.Error
            }
        }
    }
    return state
}

private fun loadComponentsData(context: Context, packageName: String): ComponentsData {
    val packageInfo = context.packageManager.getPackageInfo(
        packageName,
        PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS,
    )
    return ComponentsData(
        activities = packageInfo.activities?.toList() ?: emptyList(),
        services = packageInfo.services?.toList() ?: emptyList(),
        receivers = packageInfo.receivers?.toList() ?: emptyList(),
        providers = packageInfo.providers?.toList() ?: emptyList(),
    )
}

@Composable
internal fun ComponentGroupHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
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

@Composable
internal fun ActivityComponentItem(
    activity: ActivityInfo,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ComponentItem(
        modifier = modifier,
        icon = Icons.TwoTone.AppRegistration,
        name = activity.name.substringAfterLast('.'),
        fullName = activity.name,
        isExported = activity.exported,
        trailingContent = {
            if (activity.exported) {
                IconButton(onClick = onLaunch) {
                    Icon(
                        imageVector = Icons.TwoTone.PlayArrow,
                        contentDescription = stringResource(R.string.apps_action_launch),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}

@Composable
internal fun ServiceComponentItem(
    service: ServiceInfo,
    modifier: Modifier = Modifier,
) {
    ComponentItem(
        modifier = modifier,
        icon = Icons.TwoTone.MiscellaneousServices,
        name = service.name.substringAfterLast('.'),
        fullName = service.name,
        isExported = service.exported,
    )
}

@Composable
internal fun ReceiverComponentItem(
    receiver: ActivityInfo,
    modifier: Modifier = Modifier,
) {
    ComponentItem(
        modifier = modifier,
        icon = Icons.TwoTone.CellTower,
        name = receiver.name.substringAfterLast('.'),
        fullName = receiver.name,
        isExported = receiver.exported,
    )
}

@Composable
internal fun ProviderComponentItem(
    provider: ProviderInfo,
    modifier: Modifier = Modifier,
) {
    ComponentItem(
        modifier = modifier,
        icon = Icons.TwoTone.DataObject,
        name = provider.name.substringAfterLast('.'),
        fullName = provider.name,
        isExported = provider.exported,
    )
}

@Composable
private fun ComponentItem(
    icon: ImageVector,
    name: String,
    fullName: String,
    isExported: Boolean,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Fully-qualified name so the dedicated screen isn't limited to the short class name
            Text(
                text = fullName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isExported) {
                Text(
                    text = stringResource(R.string.apps_components_exported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        trailingContent?.invoke()
    }
}
