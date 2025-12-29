package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.apps.core.engine.AppTag
import eu.darken.butler.apps.ui.apps.items.colors
import eu.darken.butler.apps.ui.apps.items.label
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun TagFilterChip(
    modifier: Modifier = Modifier,
    tag: AppTag,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tagColors = tag.colors()
    FilterChip(
        modifier = modifier.height(28.dp),
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = tag.label(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
            )
        },
        colors = if (selected) {
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = tagColors.container,
                selectedLabelColor = tagColors.content,
            )
        } else {
            FilterChipDefaults.filterChipColors()
        },
        border = if (selected) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
    )
}

@Preview2
@Composable
private fun TagFilterChipUnselectedPreview() {
    PreviewWrapper {
        TagFilterChip(
            tag = AppTag.System,
            selected = false,
            onClick = {},
        )
    }
}

@Preview2
@Composable
private fun TagFilterChipSelectedPreview() {
    PreviewWrapper {
        TagFilterChip(
            tag = AppTag.System,
            selected = true,
            onClick = {},
        )
    }
}

@Preview2
@Composable
private fun TagFilterChipDisabledPreview() {
    PreviewWrapper {
        TagFilterChip(
            tag = AppTag.Disabled,
            selected = true,
            onClick = {},
        )
    }
}

@Preview2
@Composable
private fun TagFilterChipUserPreview() {
    PreviewWrapper {
        TagFilterChip(
            tag = AppTag.User(handleId = 10, label = "Work"),
            selected = true,
            onClick = {},
        )
    }
}
