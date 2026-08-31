package eu.darken.butler.developer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.developer.R
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.StorageVolumeInfo
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.SystemInfo

@Composable
internal fun SystemInfoSection(
    systemInfo: SystemInfo,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = contentPadding,
    ) {
        // Device Section
        item {
            SectionCard(title = stringResource(R.string.developer_system_device_header)) {
                InfoRow(label = stringResource(R.string.developer_system_device_model), value = systemInfo.deviceModel)
                InfoRow(
                    label = stringResource(R.string.developer_system_device_manufacturer),
                    value = systemInfo.deviceManufacturer
                )
                InfoRow(
                    label = stringResource(R.string.developer_system_device_api),
                    value = systemInfo.apiLevel.toString()
                )
            }
        }

        // Build Section
        item {
            SectionCard(title = stringResource(R.string.developer_system_build_header)) {
                InfoRow(label = stringResource(R.string.developer_system_build_version), value = systemInfo.versionName)
                InfoRow(
                    label = stringResource(R.string.developer_system_build_code),
                    value = systemInfo.versionCode.toString()
                )
                InfoRow(label = stringResource(R.string.developer_system_build_flavor), value = systemInfo.flavor)
                InfoRow(label = stringResource(R.string.developer_system_build_type), value = systemInfo.buildType)
                InfoRow(label = stringResource(R.string.developer_system_build_git), value = systemInfo.gitSha)
            }
        }

        // Memory Section
        item {
            SectionCard(title = stringResource(R.string.developer_system_memory_header)) {
                InfoRow(
                    label = stringResource(R.string.developer_system_memory_available),
                    value = systemInfo.memoryAvailable
                )
                InfoRow(label = stringResource(R.string.developer_system_memory_total), value = systemInfo.memoryTotal)
            }
        }

        // Storage Section
        item {
            SectionCard(title = stringResource(R.string.developer_system_storage_header)) {
                systemInfo.storageVolumes.forEach { volume ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = volume.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = volume.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            text = "${volume.freeSpace} free of ${volume.totalSpace}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SystemInfoSectionPreview() {
    SystemInfoSection(
        systemInfo = SystemInfo(
            deviceModel = "Pixel 8 Pro",
            deviceManufacturer = "Google",
            apiLevel = 34,
            versionName = "1.0.0-dev",
            versionCode = 100,
            flavor = "FOSS",
            buildType = "DEBUG",
            gitSha = "abc123def",
            memoryAvailable = "4.2 GB",
            memoryTotal = "8.0 GB",
            storageVolumes = listOf(
                StorageVolumeInfo(
                    name = "Internal Storage",
                    path = "/storage/emulated/0",
                    freeSpace = "64 GB",
                    totalSpace = "128 GB",
                ),
                StorageVolumeInfo(
                    name = "SD Card",
                    path = "/storage/1234-5678",
                    freeSpace = "28 GB",
                    totalSpace = "32 GB",
                ),
            ),
        ),
    )
}
