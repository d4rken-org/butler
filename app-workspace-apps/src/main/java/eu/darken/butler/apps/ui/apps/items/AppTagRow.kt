package eu.darken.butler.apps.ui.apps.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.core.engine.AppTag
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppTagRow(
    tags: List<AppTag>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (tags.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
    ) {
        tags.forEach { tag ->
            AppTagChip(tag = tag, compact = compact)
        }
    }
}

@Preview2
@Composable
private fun AppTagRowSinglePreview() {
    PreviewWrapper {
        AppTagRow(tags = listOf(AppTag.System))
    }
}

@Preview2
@Composable
private fun AppTagRowMultiplePreview() {
    PreviewWrapper {
        AppTagRow(
            tags = listOf(
                AppTag.Disabled,
                AppTag.System,
                AppTag.UpdatedSystem,
            )
        )
    }
}

@Preview2
@Composable
private fun AppTagRowManyPreview() {
    PreviewWrapper {
        AppTagRow(
            tags = listOf(
                AppTag.Disabled,
                AppTag.System,
                AppTag.Sideloaded,
                AppTag.Debug,
                AppTag.SplitApk,
            )
        )
    }
}
