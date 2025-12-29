package eu.darken.butler.apps.ui.apps.items

import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.apps.core.AppTag
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun AppTagChip(
    tag: AppTag,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = tag.colors()
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = tag.label(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = if (compact) 8.sp else 10.sp,
            )
        },
        modifier = modifier.height(if (compact) 16.dp else 20.dp),
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = colors.container,
            labelColor = colors.content,
        ),
    )
}

@Preview2
@Composable
private fun AppTagChipSystemPreview() {
    PreviewWrapper {
        AppTagChip(tag = AppTag.System)
    }
}

@Preview2
@Composable
private fun AppTagChipDisabledPreview() {
    PreviewWrapper {
        AppTagChip(tag = AppTag.Disabled)
    }
}

@Preview2
@Composable
private fun AppTagChipSideloadedPreview() {
    PreviewWrapper {
        AppTagChip(tag = AppTag.Sideloaded)
    }
}

@Preview2
@Composable
private fun AppTagChipSplitApkPreview() {
    PreviewWrapper {
        AppTagChip(tag = AppTag.SplitApk)
    }
}

@Preview2
@Composable
private fun AppTagChipUserWithLabelPreview() {
    PreviewWrapper {
        AppTagChip(
            tag = AppTag.User(handleId = 10, label = "Work")
        )
    }
}

@Preview2
@Composable
private fun AppTagChipUserWithoutLabelPreview() {
    PreviewWrapper {
        AppTagChip(
            tag = AppTag.User(handleId = 10)
        )
    }
}
