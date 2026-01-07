package eu.darken.butler.developer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.developer.R
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.StorageVolumeInfo
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.TestDataProgress
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.TestDataState

@Composable
internal fun TestDataSection(
    storageVolumes: List<StorageVolumeInfo>,
    testDataState: TestDataState,
    onVolumeSelected: (Int) -> Unit,
    onLargeFilesToggled: (Boolean) -> Unit,
    onNestedStructureToggled: (Boolean) -> Unit,
    onTextFilesToggled: (Boolean) -> Unit,
    onGenerateTestData: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.developer_testdata_volume_label),
            style = MaterialTheme.typography.titleMedium,
        )

        // Volume selector
        if (storageVolumes.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.developer_testdata_no_storage),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                storageVolumes.forEachIndexed { index, volume ->
                    FilterChip(
                        selected = testDataState.selectedVolumeIndex == index,
                        onClick = { onVolumeSelected(index) },
                        label = {
                            Column {
                                Text(text = volume.name, style = MaterialTheme.typography.labelMedium)
                                Text(
                                    text = "${volume.freeSpace} free",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.developer_testdata_options_header),
            style = MaterialTheme.typography.titleMedium,
        )

        // Options
        TestDataOption(
            title = stringResource(R.string.developer_testdata_large_files),
            description = stringResource(R.string.developer_testdata_large_files_desc),
            checked = testDataState.largeFilesEnabled,
            onCheckedChange = onLargeFilesToggled,
            enabled = !testDataState.progress?.isGenerating.orFalse(),
        )
        TestDataOption(
            title = stringResource(R.string.developer_testdata_nested_structure),
            description = stringResource(R.string.developer_testdata_nested_structure_desc),
            checked = testDataState.nestedStructureEnabled,
            onCheckedChange = onNestedStructureToggled,
            enabled = !testDataState.progress?.isGenerating.orFalse(),
        )
        TestDataOption(
            title = stringResource(R.string.developer_testdata_text_files),
            description = stringResource(R.string.developer_testdata_text_files_desc),
            checked = testDataState.textFilesEnabled,
            onCheckedChange = onTextFilesToggled,
            enabled = !testDataState.progress?.isGenerating.orFalse(),
        )

        // Progress indicator
        testDataState.progress?.let { progress ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (progress.isGenerating) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (progress.isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = progress.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            onClick = onGenerateTestData,
            enabled = testDataState.canGenerate && !testDataState.progress?.isGenerating.orFalse(),
        ) {
            Text(
                text = if (testDataState.progress?.isGenerating == true) {
                    stringResource(R.string.developer_testdata_generating)
                } else {
                    stringResource(R.string.developer_testdata_generate)
                }
            )
        }
    }
}

private fun Boolean?.orFalse(): Boolean = this ?: false

@Composable
private fun TestDataOption(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.4f),
            )
        }
    }
}

@Preview2
@Composable
private fun TestDataSectionPreview() {
    PreviewWrapper {
        TestDataSection(
            storageVolumes = listOf(
                StorageVolumeInfo(
                    name = "Internal",
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
            testDataState = TestDataState(
                selectedVolumeIndex = 0,
                largeFilesEnabled = false,
                nestedStructureEnabled = true,
                textFilesEnabled = true,
                progress = null,
                canGenerate = true,
            ),
            onVolumeSelected = {},
            onLargeFilesToggled = {},
            onNestedStructureToggled = {},
            onTextFilesToggled = {},
            onGenerateTestData = {},
        )
    }
}

@Preview2
@Composable
private fun TestDataSectionGeneratingPreview() {
    PreviewWrapper {
        TestDataSection(
            storageVolumes = listOf(
                StorageVolumeInfo(
                    name = "Internal",
                    path = "/storage/emulated/0",
                    freeSpace = "64 GB",
                    totalSpace = "128 GB",
                ),
            ),
            testDataState = TestDataState(
                selectedVolumeIndex = 0,
                largeFilesEnabled = true,
                nestedStructureEnabled = true,
                textFilesEnabled = false,
                progress = TestDataProgress(
                    isGenerating = true,
                    message = "Creating large files (3/8)...",
                ),
                canGenerate = true,
            ),
            onVolumeSelected = {},
            onLargeFilesToggled = {},
            onNestedStructureToggled = {},
            onTextFilesToggled = {},
            onGenerateTestData = {},
        )
    }
}

@Preview2
@Composable
private fun TestDataSectionNoStoragePreview() {
    PreviewWrapper {
        TestDataSection(
            storageVolumes = emptyList(),
            testDataState = TestDataState(
                selectedVolumeIndex = -1,
                largeFilesEnabled = false,
                nestedStructureEnabled = false,
                textFilesEnabled = false,
                progress = null,
                canGenerate = false,
            ),
            onVolumeSelected = {},
            onLargeFilesToggled = {},
            onNestedStructureToggled = {},
            onTextFilesToggled = {},
            onGenerateTestData = {},
        )
    }
}
