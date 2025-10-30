package eu.darken.butler.explorer.ui.explorer.permissions

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FlashOn
import androidx.compose.material.icons.twotone.FolderOff
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.permissions.SAFPickerGrant
import eu.darken.butler.workspace.core.permissions.WorkspaceRequirements

private fun getDescriptionForRequirements(
    requirements: WorkspaceRequirements
): Int {
    val hasSAFPicker = requirements.safPickerGrant != null
    requirements.combos.isNotEmpty()
    val allModules = requirements.combos.flatten()
    val needsStorage = SetupModule.Type.STORAGE in allModules
    val needsRoot = SetupModule.Type.ROOT in allModules
    val needsShizuku = SetupModule.Type.SHIZUKU in allModules
    val needsStorageOnly = needsStorage && !needsRoot && !needsShizuku

    return when {
        // Storage permission only
        needsStorageOnly && !hasSAFPicker ->
            R.string.explorer_permission_generic_description

        // SAF picker + Root/Shizuku options
        hasSAFPicker && (needsRoot || needsShizuku) ->
            R.string.explorer_permission_multiple_options_description

        // SAF picker only
        hasSAFPicker ->
            R.string.explorer_permission_saf_workaround_description

        // Root OR Shizuku (alternatives)
        needsRoot && needsShizuku && requirements.combos.size > 1 ->
            R.string.explorer_permission_setup_both_description

        // Root only
        needsRoot && !needsShizuku ->
            R.string.explorer_permission_setup_root_description

        // Shizuku only
        needsShizuku && !needsRoot ->
            R.string.explorer_permission_setup_shizuku_description

        // Fallback
        else -> R.string.explorer_permission_generic_description
    }
}

@Composable
fun PermissionRequestCard(
    setupRequirements: WorkspaceRequirements,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier,
    onLaunchSAFPicker: ((SAFPickerGrant) -> Unit)? = null,
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
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.explorer_permission_required_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // General explanation
            Text(
                text = stringResource(getDescriptionForRequirements(setupRequirements)),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Determine which setup options are needed
            val allModules = setupRequirements.combos.flatten()
            val needsStorage = SetupModule.Type.STORAGE in allModules
            val needsRootOrShizuku = SetupModule.Type.ROOT in allModules || SetupModule.Type.SHIZUKU in allModules
            val needsStorageOnly = needsStorage && !needsRootOrShizuku

            // Track if we've shown any cards (for spacing)
            var hasShownCard = false

            // Show Quick Access option card if SAF picker available
            if (setupRequirements.safPickerGrant != null && onLaunchSAFPicker != null) {
                val grant = setupRequirements.safPickerGrant!!
                PermissionOptionCard(
                    icon = Icons.TwoTone.FlashOn,
                    title = stringResource(R.string.explorer_permission_option_picker_title),
                    description = stringResource(R.string.explorer_permission_option_picker_description),
                    actionLabel = stringResource(R.string.explorer_permission_option_picker_action),
                    onAction = { onLaunchSAFPicker(grant) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                hasShownCard = true
            }

            // Show Storage Access option card if only storage permission needed
            if (needsStorageOnly) {
                if (hasShownCard) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                PermissionOptionCard(
                    icon = Icons.TwoTone.FolderOpen,
                    title = stringResource(R.string.explorer_permission_option_storage_title),
                    description = stringResource(R.string.explorer_permission_option_storage_description),
                    actionLabel = stringResource(R.string.explorer_permission_option_storage_action),
                    onAction = onNavigateToSetup,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                hasShownCard = true
            }

            // Show Full Access option card if Root/Shizuku needed
            if (needsRootOrShizuku) {
                if (hasShownCard) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                PermissionOptionCard(
                    icon = Icons.TwoTone.Settings,
                    title = stringResource(R.string.explorer_permission_option_setup_title),
                    description = stringResource(R.string.explorer_permission_option_setup_description),
                    actionLabel = stringResource(R.string.explorer_permission_option_setup_action),
                    onAction = onNavigateToSetup,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

@Preview2
@Composable
private fun PermissionRequestCardBothOptionsPreview() {
    PreviewWrapper {
        PermissionRequestCard(
            setupRequirements = WorkspaceRequirements(
                safPickerGrant = SAFPickerGrant(
                    intent = Intent(),
                    targetPath = LocalPath.build("/storage/emulated/0/Android/data"),
                ),
                combos = setOf(
                    setOf(SetupModule.Type.ROOT),
                    setOf(SetupModule.Type.SHIZUKU),
                ),
            ),
            onNavigateToSetup = {},
            onLaunchSAFPicker = {},
        )
    }
}

@Preview2
@Composable
private fun PermissionRequestCardSAFOnlyPreview() {
    PreviewWrapper {
        PermissionRequestCard(
            setupRequirements = WorkspaceRequirements(
                safPickerGrant = SAFPickerGrant(
                    intent = Intent(),
                    targetPath = LocalPath.build("/storage/emulated/0/Android/data"),
                ),
            ),
            onNavigateToSetup = {},
            onLaunchSAFPicker = {},
        )
    }
}

@Preview2
@Composable
private fun PermissionRequestCardSetupOnlyPreview() {
    PreviewWrapper {
        PermissionRequestCard(
            setupRequirements = WorkspaceRequirements(
                combos = setOf(
                    setOf(SetupModule.Type.ROOT),
                    setOf(SetupModule.Type.SHIZUKU),
                ),
            ),
            onNavigateToSetup = {},
        )
    }
}

@Preview2
@Composable
private fun PermissionRequestCardStorageOnlyPreview() {
    PreviewWrapper {
        PermissionRequestCard(
            setupRequirements = WorkspaceRequirements(
                combos = setOf(setOf(SetupModule.Type.STORAGE)),
            ),
            onNavigateToSetup = {},
        )
    }
}