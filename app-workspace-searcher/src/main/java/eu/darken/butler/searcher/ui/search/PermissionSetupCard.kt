package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.workspace.core.permissions.PermissionState
import eu.darken.butler.workspace.core.permissions.SetupRequirement

@Composable
fun PermissionSetupCard(
    searchPath: APath<*>,
    permissionState: PermissionState,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val primaryPermission = permissionState.requirements.firstOrNull()?.permission
                Icon(
                    imageVector = when (primaryPermission) {
                        is Permission.MANAGE_EXTERNAL_STORAGE -> Icons.TwoTone.Storage
                        is Permission.WRITE_EXTERNAL_STORAGE -> Icons.TwoTone.Storage
                        else -> Icons.TwoTone.Storage
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                Text(
                    text = stringResource(eu.darken.butler.common.R.string.setup_required_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = stringResource(eu.darken.butler.common.R.string.setup_required_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            Text(
                text = searchPath.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            // Setup requirement description if available
            val primaryRequirement = permissionState.requirements.firstOrNull()
            primaryRequirement?.let { requirement ->
                Text(
                    text = requirement.description.get(LocalContext.current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            Button(
                onClick = onOpenSetup,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(eu.darken.butler.common.R.string.setup_required_card_setup_action),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Preview2
@Composable
private fun PermissionSetupCardPreview() {
    PreviewWrapper {
        PermissionSetupCard(
            searchPath = RawPath.build("/storage/emulated/0/Documents"),
            permissionState = PermissionState(
                requirements = listOf(
                    SetupRequirement(
                        permission = Permission.MANAGE_EXTERNAL_STORAGE,
                        isRequired = true,
                    )
                ),
                hasSufficientPermissions = false,
                missingCritical = listOf(Permission.MANAGE_EXTERNAL_STORAGE),
            ),
            onOpenSetup = {},
        )
    }
}