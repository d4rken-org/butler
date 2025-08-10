package eu.darken.butler.explorer.ui.explorer.rows

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun ApkFileRow(
    item: ExplorerPathItem.ApkFile,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    showSelection: Boolean,
    modifier: Modifier = Modifier
) {
    FileRowBase(
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        showSelection = showSelection,
        modifier = modifier,
        leadingContent = {
            // TODO: Replace with AsyncImage when Coil integration is complete
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = stringResource(R.string.explorer_file_apk_content_desc),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(32.dp)
            )
        },
        primaryText = item.displayName,
        secondaryText = buildString {
            item.packageName?.let { 
                append(it)
                append(" • ")
            }
            item.versionName?.let { 
                append("v$it")
                append(" • ")
            }
            append(item.displaySize)
        }
    )
}

@Preview2
@Composable
private fun ApkFileRowPreview() {
    ApkFileRow(
        item = MockDataProvider.createMockApkFile(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun ApkFileRowSelectedPreview() {
    ApkFileRow(
        item = MockDataProvider.createMockApkFile("butler.apk", "eu.darken.butler", "2.1.4", "Butler File Manager"),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}