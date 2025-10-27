package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.engine.AppsState

@Composable
fun FilterOptionsDialog(
    currentFilter: AppsState.FilterConfig,
    onDismiss: () -> Unit,
    onApply: (AppsState.FilterConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSystemApps by remember { mutableStateOf(currentFilter.showSystemApps) }
    var showUserApps by remember { mutableStateOf(currentFilter.showUserApps) }
    var showEnabledApps by remember { mutableStateOf(currentFilter.showEnabledApps) }
    var showDisabledApps by remember { mutableStateOf(currentFilter.showDisabledApps) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Filter apps")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // System Apps
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it }
                    )
                    Text(
                        text = stringResource(R.string.apps_filter_system_apps),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                // User Apps
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = showUserApps,
                        onCheckedChange = { showUserApps = it }
                    )
                    Text(
                        text = stringResource(R.string.apps_filter_user_apps),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                // Enabled Apps
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = showEnabledApps,
                        onCheckedChange = { showEnabledApps = it }
                    )
                    Text(
                        text = stringResource(R.string.apps_filter_enabled),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                // Disabled Apps
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = showDisabledApps,
                        onCheckedChange = { showDisabledApps = it }
                    )
                    Text(
                        text = stringResource(R.string.apps_filter_disabled),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newFilter = AppsState.FilterConfig(
                        showSystemApps = showSystemApps,
                        showUserApps = showUserApps,
                        showEnabledApps = showEnabledApps,
                        showDisabledApps = showDisabledApps,
                    )
                    onApply(newFilter)
                    onDismiss()
                }
            ) {
                Text(text = "Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        },
        modifier = modifier,
    )
}
