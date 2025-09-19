package eu.darken.butler.explorer.ui.explorer.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderOff
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.permissions.PermissionState
import eu.darken.butler.workspace.core.permissions.SetupRequirement

@Composable
fun PermissionRequestCard(
    permissionState: PermissionState,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Icon - Use storage/folder icon instead of lock
            Icon(
                imageVector = when {
                    permissionState.missingCritical.any { it is Permission.MANAGE_EXTERNAL_STORAGE } -> {
                        Icons.TwoTone.Storage
                    }
                    permissionState.missingCritical.isNotEmpty() -> {
                        Icons.TwoTone.Folder
                    }
                    else -> {
                        Icons.TwoTone.FolderOff
                    }
                },
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = stringResource(R.string.explorer_permission_required_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Description
            val primaryPermission = permissionState.requirements.firstOrNull()
            val description = when (primaryPermission?.permission) {
                is Permission.MANAGE_EXTERNAL_STORAGE -> {
                    stringResource(R.string.explorer_permission_storage_manage_description)
                }
                is Permission.WRITE_EXTERNAL_STORAGE -> {
                    stringResource(R.string.explorer_permission_storage_write_description)
                }
                else -> {
                    stringResource(R.string.explorer_permission_generic_description)
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Setup Button
            if (permissionState.missingCritical.isNotEmpty()) {
                Button(
                    onClick = onNavigateToSetup,
                    modifier = Modifier.fillMaxWidth(0.8f),
                ) {
                    Text(
                        text = stringResource(R.string.explorer_permission_setup_action),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun PermissionRequestCardPreview() {
    PreviewWrapper {
        PermissionRequestCard(
            permissionState = PermissionState(
                requirements = listOf(
                    SetupRequirement(
                        permission = Permission.MANAGE_EXTERNAL_STORAGE,
                        isRequired = true,
                        description = "Access files and folders".toCaString(),
                    )
                ),
                hasSufficientPermissions = false,
                missingCritical = listOf(Permission.MANAGE_EXTERNAL_STORAGE),
            ),
            onNavigateToSetup = {},
        )
    }
}