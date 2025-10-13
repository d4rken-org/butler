package eu.darken.butler.searcher.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.ui.search.rows.FileInfo
import eu.darken.butler.searcher.ui.search.rows.FileRowData
import eu.darken.butler.searcher.ui.search.rows.StandardFileIcon
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableFileRow(
    data: FileRowData,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animation values
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "background_color"
    )

    val checkboxAlpha by animateFloatAsState(
        targetValue = if (isSelectionMode) 1f else 0f,
        label = "checkbox_alpha"
    )

    val checkboxScale by animateFloatAsState(
        targetValue = if (isSelectionMode) 1f else 0.7f,
        label = "checkbox_scale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox (visible in selection mode)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .alpha(checkboxAlpha)
                    .scale(checkboxScale),
                contentAlignment = Alignment.Center
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() }
                    )
                }
            }

            if (isSelectionMode) {
                Spacer(modifier = Modifier.width(12.dp))
            }

            // File icon
            StandardFileIcon(data)

            Spacer(modifier = Modifier.width(12.dp))

            // File info
            FileInfo(
                data = data,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview2
@Composable
private fun SelectableFileRowPreview() {
    val fileData = FileRowData(
        name = "example.txt",
        path = "/storage/emulated/0/Documents/example.txt",
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Clock.System.now() - 3600.seconds
    )

    PreviewWrapper {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            // Normal mode
            SelectableFileRow(
                data = fileData,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongPress = {}
            )

            // Selection mode - unselected
            SelectableFileRow(
                data = fileData,
                isSelected = false,
                isSelectionMode = true,
                onClick = {},
                onLongPress = {}
            )

            // Selection mode - selected
            SelectableFileRow(
                data = fileData,
                isSelected = true,
                isSelectionMode = true,
                onClick = {},
                onLongPress = {}
            )
        }
    }
}