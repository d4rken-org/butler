package eu.darken.butler.apps.ui.details

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
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
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log

data class ComponentsData(
    val activities: List<ActivityInfo> = emptyList(),
    val services: List<ServiceInfo> = emptyList(),
    val receivers: List<ActivityInfo> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
)

@Composable
fun ComponentsSection(
    modifier: Modifier = Modifier,
    app: AppInfo?,
    onLaunchActivity: (ActivityInfo) -> Unit,
) {
    if (app == null) return

    val context = LocalContext.current

    val componentsData = remember(app.packageName) {
        getComponentsData(context, app.packageName)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Activities
        if (componentsData.activities.isNotEmpty()) {
            ComponentGroupHeader(
                title = stringResource(R.string.apps_components_activities_label),
                count = componentsData.activities.size,
            )
            componentsData.activities.take(10).forEach { activity ->
                ActivityComponentItem(
                    activity = activity,
                    onLaunch = { onLaunchActivity(activity) },
                )
            }
            if (componentsData.activities.size > 10) {
                Text(
                    text = stringResource(R.string.apps_components_and_more, componentsData.activities.size - 10),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }

        // Services
        if (componentsData.services.isNotEmpty()) {
            ComponentGroupHeader(
                title = stringResource(R.string.apps_components_services_label),
                count = componentsData.services.size,
            )
            componentsData.services.take(10).forEach { service ->
                ServiceComponentItem(service = service)
            }
            if (componentsData.services.size > 10) {
                Text(
                    text = stringResource(R.string.apps_components_and_more, componentsData.services.size - 10),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }

        // Receivers
        if (componentsData.receivers.isNotEmpty()) {
            ComponentGroupHeader(
                title = stringResource(R.string.apps_components_receivers_label),
                count = componentsData.receivers.size,
            )
            componentsData.receivers.take(10).forEach { receiver ->
                ReceiverComponentItem(receiver = receiver)
            }
            if (componentsData.receivers.size > 10) {
                Text(
                    text = stringResource(R.string.apps_components_and_more, componentsData.receivers.size - 10),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }

        // Providers
        if (componentsData.providers.isNotEmpty()) {
            ComponentGroupHeader(
                title = stringResource(R.string.apps_components_providers_label),
                count = componentsData.providers.size,
            )
            componentsData.providers.take(10).forEach { provider ->
                ProviderComponentItem(provider = provider)
            }
            if (componentsData.providers.size > 10) {
                Text(
                    text = stringResource(R.string.apps_components_and_more, componentsData.providers.size - 10),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        // Empty state
        if (componentsData.activities.isEmpty() &&
            componentsData.services.isEmpty() &&
            componentsData.receivers.isEmpty() &&
            componentsData.providers.isEmpty()
        ) {
            Text(
                text = stringResource(R.string.apps_components_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun ComponentGroupHeader(
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
private fun ActivityComponentItem(
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
private fun ServiceComponentItem(
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
private fun ReceiverComponentItem(
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
private fun ProviderComponentItem(
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isExported) {
                    Text(
                        text = stringResource(R.string.apps_components_exported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        trailingContent?.invoke()
    }
}

private fun getComponentsData(context: Context, packageName: String): ComponentsData {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS,
        )

        ComponentsData(
            activities = packageInfo.activities?.toList() ?: emptyList(),
            services = packageInfo.services?.toList() ?: emptyList(),
            receivers = packageInfo.receivers?.toList() ?: emptyList(),
            providers = packageInfo.providers?.toList() ?: emptyList(),
        )
    } catch (e: Exception) {
        log("ComponentsSection", WARN) { "Failed to get components for $packageName: $e" }
        ComponentsData()
    }
}

fun launchActivity(context: Context, activityInfo: ActivityInfo) {
    try {
        val intent = Intent().apply {
            component = ComponentName(activityInfo.packageName, activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        log("ComponentsSection", WARN) { "Failed to launch activity ${activityInfo.name}: $e" }
    }
}

@Preview2
@Composable
private fun ComponentsSectionPreview() {
    PreviewWrapper {
        ComponentsSection(
            app = null,
            onLaunchActivity = {},
        )
    }
}
