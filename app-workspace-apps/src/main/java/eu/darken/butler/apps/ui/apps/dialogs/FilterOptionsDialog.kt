package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
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
    var selectedAppType by remember { mutableStateOf(currentFilter.appType) }
    var selectedStatus by remember { mutableStateOf(currentFilter.status) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.apps_action_filter))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.apps_filter_app_type_label),
                    style = MaterialTheme.typography.titleSmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(modifier = Modifier.selectableGroup()) {
                    AppsState.FilterConfig.AppType.entries.forEach { appType ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedAppType == appType,
                                    onClick = { selectedAppType = appType },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedAppType == appType,
                                onClick = null,
                            )
                            Text(
                                text = when (appType) {
                                    AppsState.FilterConfig.AppType.ALL -> stringResource(R.string.apps_filter_type_all)
                                    AppsState.FilterConfig.AppType.USER -> stringResource(R.string.apps_filter_type_user)
                                    AppsState.FilterConfig.AppType.SYSTEM -> stringResource(R.string.apps_filter_type_system)
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.apps_filter_status_label),
                    style = MaterialTheme.typography.titleSmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(modifier = Modifier.selectableGroup()) {
                    AppsState.FilterConfig.Status.entries.forEach { status ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedStatus == status,
                                    onClick = { selectedStatus = status },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedStatus == status,
                                onClick = null,
                            )
                            Text(
                                text = when (status) {
                                    AppsState.FilterConfig.Status.ALL -> stringResource(R.string.apps_filter_status_all)
                                    AppsState.FilterConfig.Status.ENABLED -> stringResource(R.string.apps_filter_status_enabled)
                                    AppsState.FilterConfig.Status.DISABLED -> stringResource(R.string.apps_filter_status_disabled)
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        onApply(AppsState.FilterConfig())
                        onDismiss()
                    }
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_reset_action))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onApply(
                            AppsState.FilterConfig(
                                appType = selectedAppType,
                                status = selectedStatus,
                            )
                        )
                        onDismiss()
                    }
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_apply_action))
                }
            }
        },
        dismissButton = null,
        modifier = modifier,
    )
}
