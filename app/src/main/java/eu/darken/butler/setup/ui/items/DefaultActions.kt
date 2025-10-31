package eu.darken.butler.setup.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.notification.NotificationSetupModule

@Composable
fun DefaultActions(
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit
) {
    val state = item.state as? SetupModule.State.Current
    val isCompleted = state?.isComplete == true

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status indicator and message
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SetupStateIndicator(
                state = item.state,
                isRequired = item.isRequired
            )

            Text(
                text = getStatusMessage(item.state, item.isRequired),
                style = MaterialTheme.typography.bodyMedium,
                color = getStatusColor(item.state, item.isRequired)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Grant access button
        Button(
            onClick = {
                onExecuteAction(
                    if (isCompleted) SetupAction.Refresh else SetupAction.RequestPermission
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isCompleted
        ) {
            Text(
                text = if (isCompleted) {
                    stringResource(R.string.setup_access_granted_label)
                } else {
                    stringResource(R.string.setup_grant_access_label)
                }
            )
        }
    }
}

@Preview2
@Composable
private fun DefaultActionsNotGrantedPreview() {
    PreviewWrapper {
        DefaultActions(
            item = SetupItem(
                type = SetupModule.Type.NOTIFICATION,
                state = NotificationSetupModule.Result(
                    missingPermission = setOf(Permission.POST_NOTIFICATIONS),
                ),
                isRequired = true,
                priority = 3,
            ),
            onExecuteAction = {}
        )
    }
}

@Preview2
@Composable
private fun DefaultActionsGrantedPreview() {
    PreviewWrapper {
        DefaultActions(
            item = SetupItem(
                type = SetupModule.Type.NOTIFICATION,
                state = NotificationSetupModule.Result(
                    missingPermission = emptySet(),
                ),
                isRequired = false,
                priority = 3,
            ),
            onExecuteAction = {}
        )
    }
}