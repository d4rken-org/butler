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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FlashOn
import androidx.compose.material.icons.twotone.FolderOff
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.permissions.core.SAFPickerGrant
import eu.darken.butler.setup.core.SetupModule

private fun getDescriptionForRequirements(
    requirements: PathRequirements
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
    setupRequirements: PathRequirements,
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
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Icon
            Icon(
                imageVector = Icons.TwoTone.FolderOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.explorer_permission_required_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // General explanation
            Text(
                text = stringResource(getDescriptionForRequirements(setupRequirements)),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Determine which setup options are needed
            val allModules = setupRequirements.combos.flatten()
            val needsStorage = SetupModule.Type.STORAGE in allModules
            val needsRootOrShizuku = SetupModule.Type.ROOT in allModules || SetupModule.Type.SHIZUKU in allModules
            val needsStorageOnly = needsStorage && !needsRootOrShizuku

            // Show Quick Access option card if SAF picker available
            if (setupRequirements.safPickerGrant != null && onLaunchSAFPicker != null) {
                val grant = setupRequirements.safPickerGrant!!
                PermissionOptionCard(
                    icon = Icons.TwoTone.FlashOn,
                    title = stringResource(R.string.explorer_permission_option_picker_title),
                    description = stringResource(R.string.explorer_permission_option_picker_description),
                    actionLabel = stringResource(R.string.explorer_permission_option_picker_action),
                    onAction = { onLaunchSAFPicker(grant) },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Show Storage Access option card if only storage permission needed
            if (needsStorageOnly) {
                PermissionOptionCard(
                    icon = Icons.TwoTone.FolderOpen,
                    title = stringResource(R.string.explorer_permission_option_storage_title),
                    description = stringResource(R.string.explorer_permission_option_storage_description),
                    actionLabel = stringResource(R.string.explorer_permission_option_storage_action),
                    onAction = onNavigateToSetup,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Show Full Access option card if Root/Shizuku needed
            if (needsRootOrShizuku) {
                PermissionOptionCard(
                    icon = Icons.TwoTone.Settings,
                    title = stringResource(R.string.explorer_permission_option_setup_title),
                    description = stringResource(R.string.explorer_permission_option_setup_description),
                    actionLabel = stringResource(R.string.explorer_permission_option_setup_action),
                    onAction = onNavigateToSetup,
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // Icon + Title Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Description
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Button aligned to bottom-right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onAction) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Preview2
@Composable
private fun PermissionRequestCardBothOptionsPreview() {
    PreviewWrapper {
        PermissionRequestCard(
            setupRequirements = PathRequirements(
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
            setupRequirements = PathRequirements(
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
            setupRequirements = PathRequirements(
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
            setupRequirements = PathRequirements(
                combos = setOf(setOf(SetupModule.Type.STORAGE)),
            ),
            onNavigateToSetup = {},
        )
    }
}