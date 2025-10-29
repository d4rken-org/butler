package eu.darken.butler.setup.ui.items

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.saf.SAFSetupModule

@Composable
fun SAFActions(
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit
) {
    val state = item.state as? SAFSetupModule.Result
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status indicator
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

        Spacer(modifier = Modifier.height(4.dp))

        // Path access cards
        state?.paths?.forEach { pathAccess ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (pathAccess.hasAccess) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Label and path
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (pathAccess.hasAccess) {
                                Icons.TwoTone.CheckCircle
                            } else {
                                Icons.TwoTone.FolderOpen
                            },
                            contentDescription = null,
                            tint = if (pathAccess.hasAccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = pathAccess.label.get(context),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = pathAccess.localPath.path,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Grant button
                    if (!pathAccess.hasAccess) {
                        Button(
                            onClick = {
                                onExecuteAction(
                                    SetupAction.GrantSAFAccess(pathAccess.safPath.pathUri)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.setup_grant_access_label))
                        }
                    } else {
                        // Show granted status
                        Text(
                            text = stringResource(R.string.setup_access_granted_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun SAFActionsPreview() {
    PreviewWrapper {
        val mockTreeUri = "content://com.android.externalstorage.documents/tree/primary%3A"
        val mockPath1 = SAFSetupModule.Result.PathAccess(
            label = "Public Storage".toCaString(),
            safPath = SAFPath.build(mockTreeUri, "storage", "emulated", "0"),
            localPath = LocalPath.build("/storage/emulated/0"),
            uriPermission = null,
            grantIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
        )
        val mockPath2 = SAFSetupModule.Result.PathAccess(
            label = "App Data".toCaString(),
            safPath = SAFPath.build(mockTreeUri, "storage", "emulated", "0", "Android", "data"),
            localPath = LocalPath.build("/storage/emulated/0/Android/data"),
            uriPermission = null,
            grantIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
        )

        SAFActions(
            item = SetupItem(
                type = SetupModule.Type.SAF,
                state = SAFSetupModule.Result(
                    paths = listOf(mockPath1, mockPath2)
                ),
                isRequired = true,
                priority = 2,
            ),
            onExecuteAction = {}
        )
    }
}
