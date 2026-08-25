package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.workspace.R

enum class InfoValueStyle {
    NORMAL,
    MONOSPACE,
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            content = content,
        )
    }
}

@Composable
fun InfoField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    valueStyle: InfoValueStyle = InfoValueStyle.NORMAL,
) {
    val copyLabel = stringResource(R.string.workspace_file_info_copy_action)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onCopy != null) {
                    Modifier
                        .clickable(onClickLabel = copyLabel) { onCopy() }
                        .heightIn(min = 48.dp)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onCopy != null) {
                Icon(
                    imageVector = Icons.TwoTone.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = value,
            style = when (valueStyle) {
                InfoValueStyle.NORMAL -> MaterialTheme.typography.bodyMedium
                InfoValueStyle.MONOSPACE -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = when (valueStyle) {
                InfoValueStyle.NORMAL -> 3
                InfoValueStyle.MONOSPACE -> Int.MAX_VALUE
            },
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun InfoFieldPreview() {
    InfoField(
        label = "Size",
        value = "51.2 kB",
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun InfoFieldCopyablePreview() {
    InfoField(
        label = "Name",
        value = "annual-report-2026-final.txt",
        onCopy = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun InfoFieldMonospacePreview() {
    InfoField(
        label = "Path",
        value = "/storage/emulated/0/Documents/Reports/2026/annual-report-2026-final.txt",
        onCopy = {},
        valueStyle = InfoValueStyle.MONOSPACE,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun InfoCardPreview() {
    InfoCard {
        InfoField(
            label = "Name",
            value = "annual-report-2026-final.txt",
            onCopy = {},
        )
        InfoField(
            label = "Path",
            value = "/storage/emulated/0/Documents/Reports/2026/annual-report-2026-final.txt",
            onCopy = {},
            valueStyle = InfoValueStyle.MONOSPACE,
        )
        InfoField(
            label = "Type",
            value = "text/plain",
        )
        InfoField(
            label = "Size",
            value = "51.2 kB",
        )
        InfoField(
            label = "Permissions",
            value = "0644",
            valueStyle = InfoValueStyle.MONOSPACE,
        )
    }
}
