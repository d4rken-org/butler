package eu.darken.butler.developer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.*

@Composable
internal fun TestDataSection(
    storageVolumes: List<StorageVolumeInfo>,
    testDataState: TestDataState,
    onVolumeToggled: (Int, Boolean) -> Unit,
    onLargeFilesToggled: (Boolean) -> Unit,
    onNestedStructureToggled: (Boolean) -> Unit,
    onTextFilesToggled: (Boolean) -> Unit,
    onGenerateTestData: () -> Unit,
    onDeleteLargeFilesToggled: (Boolean) -> Unit,
    onDeleteNestedStructureToggled: (Boolean) -> Unit,
    onDeleteTextFilesToggled: (Boolean) -> Unit,
    onDeleteTestData: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.developer_testdata_volume_label),
            color = MaterialTheme.colorScheme.onSurface,
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                storageVolumes.forEachIndexed { index, volume ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = index in testDataState.selectedVolumeIndices,
                            onCheckedChange = { checked -> onVolumeToggled(index, checked) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = volume.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${volume.freeSpace} free",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.developer_testdata_options_header),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )

        // Options
        TestDataOption(
            title = stringResource(R.string.developer_testdata_large_files),
            description = stringResource(R.string.developer_testdata_large_files_desc),
            checked = testDataState.largeFilesEnabled,
            onCheckedChange = onLargeFilesToggled,
        )
        TestDataOption(
            title = stringResource(R.string.developer_testdata_nested_structure),
            description = stringResource(R.string.developer_testdata_nested_structure_desc),
            checked = testDataState.nestedStructureEnabled,
            onCheckedChange = onNestedStructureToggled,
        )
        TestDataOption(
            title = stringResource(R.string.developer_testdata_text_files),
            description = stringResource(R.string.developer_testdata_text_files_desc),
            checked = testDataState.textFilesEnabled,
            onCheckedChange = onTextFilesToggled,
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onGenerateTestData,
            enabled = testDataState.canGenerate,
        ) {
            Text(text = stringResource(R.string.developer_testdata_generate))
        }

        // Delete Test Data section
        Text(
            text = stringResource(R.string.developer_testdata_delete_header),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )

        TestDataOption(
            title = stringResource(R.string.developer_testdata_large_files),
            description = stringResource(R.string.developer_testdata_large_files_desc),
            checked = testDataState.deleteLargeFilesEnabled,
            onCheckedChange = onDeleteLargeFilesToggled,
        )
        TestDataOption(
            title = stringResource(R.string.developer_testdata_nested_structure),
            description = stringResource(R.string.developer_testdata_nested_structure_desc),
            checked = testDataState.deleteNestedStructureEnabled,
            onCheckedChange = onDeleteNestedStructureToggled,
        )
        TestDataOption(
            title = stringResource(R.string.developer_testdata_text_files),
            description = stringResource(R.string.developer_testdata_text_files_desc),
            checked = testDataState.deleteTextFilesEnabled,
            onCheckedChange = onDeleteTextFilesToggled,
        )

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            onClick = onDeleteTestData,
            enabled = testDataState.canDelete,
        ) {
            Text(text = stringResource(R.string.developer_testdata_delete_action))
        }
    }
}

@Composable
private fun TestDataOption(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
                selectedVolumeIndices = setOf(0),
                largeFilesEnabled = false,
                nestedStructureEnabled = true,
                textFilesEnabled = true,
                canGenerate = true,
                deleteLargeFilesEnabled = false,
                deleteNestedStructureEnabled = false,
                deleteTextFilesEnabled = true,
                canDelete = true,
            ),
            onVolumeToggled = { _, _ -> },
            onLargeFilesToggled = {},
            onNestedStructureToggled = {},
            onTextFilesToggled = {},
            onGenerateTestData = {},
            onDeleteLargeFilesToggled = {},
            onDeleteNestedStructureToggled = {},
            onDeleteTextFilesToggled = {},
            onDeleteTestData = {},
        )
    }
}

@Preview2
@Composable
private fun TestDataSectionAllEnabledPreview() {
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
                selectedVolumeIndices = setOf(0),
                largeFilesEnabled = true,
                nestedStructureEnabled = true,
                textFilesEnabled = true,
                canGenerate = true,
                deleteLargeFilesEnabled = true,
                deleteNestedStructureEnabled = true,
                deleteTextFilesEnabled = true,
                canDelete = true,
            ),
            onVolumeToggled = { _, _ -> },
            onLargeFilesToggled = {},
            onNestedStructureToggled = {},
            onTextFilesToggled = {},
            onGenerateTestData = {},
            onDeleteLargeFilesToggled = {},
            onDeleteNestedStructureToggled = {},
            onDeleteTextFilesToggled = {},
            onDeleteTestData = {},
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
                selectedVolumeIndices = emptySet(),
                largeFilesEnabled = false,
                nestedStructureEnabled = false,
                textFilesEnabled = false,
                canGenerate = false,
                deleteLargeFilesEnabled = false,
                deleteNestedStructureEnabled = false,
                deleteTextFilesEnabled = false,
                canDelete = false,
            ),
            onVolumeToggled = { _, _ -> },
            onLargeFilesToggled = {},
            onNestedStructureToggled = {},
            onTextFilesToggled = {},
            onGenerateTestData = {},
            onDeleteLargeFilesToggled = {},
            onDeleteNestedStructureToggled = {},
            onDeleteTextFilesToggled = {},
            onDeleteTestData = {},
        )
    }
}
