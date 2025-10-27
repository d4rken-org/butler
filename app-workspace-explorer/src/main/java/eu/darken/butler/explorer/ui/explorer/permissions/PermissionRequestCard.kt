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
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.permissions.WorkspaceRequirements

@Composable
fun PermissionRequestCard(
    setupRequirements: WorkspaceRequirements,
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
                imageVector = Icons.TwoTone.FolderOff,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.explorer_permission_required_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.explorer_permission_generic_description),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (setupRequirements.needsSetup) {
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
            setupRequirements = WorkspaceRequirements(
                combos = setOf(
                    setOf(SetupModule.Type.STORAGE, SetupModule.Type.SAF),
                    setOf(SetupModule.Type.STORAGE, SetupModule.Type.ROOT),
                ),
            ),
            onNavigateToSetup = {},
        )
    }
}