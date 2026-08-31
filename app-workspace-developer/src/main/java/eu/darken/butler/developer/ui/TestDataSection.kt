package eu.darken.butler.developer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.developer.R
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.*

@Composable
internal fun TestDataSection(
    testDataState: TestDataState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onAddPath: () -> Unit,
    onRemovePath: (APath<*>) -> Unit,
    onLargeFilesToggled: (Boolean) -> Unit,
    onNestedStructureToggled: (Boolean) -> Unit,
    onTextFilesToggled: (Boolean) -> Unit,
    onGenerateTestData: () -> Unit,
    onGenerateTestHistory: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.developer_testdata_target_paths_label),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )

        // Target paths list
        if (testDataState.targetPaths.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.developer_testdata_no_paths),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                testDataState.targetPaths.forEach { pathInfo ->
                    TargetPathItem(
                        pathInfo = pathInfo,
                        onRemove = { onRemovePath(pathInfo.path) },
                    )
                }
            }
        }

        // Add path button
        OutlinedButton(
            onClick = onAddPath,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.TwoTone.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.developer_testdata_add_path_action))
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
            Text(text = stringResource(R.string.developer_testdata_generate_action))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.developer_testdata_history_header),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.developer_testdata_history_desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            onClick = onGenerateTestHistory,
        ) {
            Text(text = stringResource(R.string.developer_testdata_history_generate_action))
        }
    }
}

@Composable
private fun TargetPathItem(
    pathInfo: TargetPathInfo,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pathInfo.displayPath,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.TwoTone.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TestDataSectionPreview() {
    TestDataSection(
        testDataState = TestDataState(
            targetPaths = listOf(
                TargetPathInfo(
                    path = LocalPath.build("/storage/emulated/0"),
                    displayPath = "/storage/emulated/0",
                ),
                TargetPathInfo(
                    path = LocalPath.build("/storage/1234-5678"),
                    displayPath = "/storage/1234-5678",
                ),
            ),
            largeFilesEnabled = false,
            nestedStructureEnabled = true,
            textFilesEnabled = true,
            canGenerate = true,
        ),
        onAddPath = {},
        onRemovePath = {},
        onLargeFilesToggled = {},
        onNestedStructureToggled = {},
        onTextFilesToggled = {},
        onGenerateTestData = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TestDataSectionAllEnabledPreview() {
    TestDataSection(
        testDataState = TestDataState(
            targetPaths = listOf(
                TargetPathInfo(
                    path = LocalPath.build("/storage/emulated/0"),
                    displayPath = "/storage/emulated/0",
                ),
            ),
            largeFilesEnabled = true,
            nestedStructureEnabled = true,
            textFilesEnabled = true,
            canGenerate = true,
        ),
        onAddPath = {},
        onRemovePath = {},
        onLargeFilesToggled = {},
        onNestedStructureToggled = {},
        onTextFilesToggled = {},
        onGenerateTestData = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TestDataSectionNoPathsPreview() {
    TestDataSection(
        testDataState = TestDataState(
            targetPaths = emptyList(),
            largeFilesEnabled = false,
            nestedStructureEnabled = false,
            textFilesEnabled = false,
            canGenerate = false,
        ),
        onAddPath = {},
        onRemovePath = {},
        onLargeFilesToggled = {},
        onNestedStructureToggled = {},
        onTextFilesToggled = {},
        onGenerateTestData = {},
    )
}
