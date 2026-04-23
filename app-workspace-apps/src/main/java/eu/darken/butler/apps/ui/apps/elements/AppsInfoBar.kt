package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.apps.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.InfoChip
import eu.darken.butler.workspace.ui.WorkspaceInfoBar

@Composable
fun AppsInfoBar(
    modifier: Modifier = Modifier,
    userAppsCount: Int = 0,
    systemAppsCount: Int = 0,
    selectedCount: Int = 0,
    onClearSelection: () -> Unit = {},
) {
    WorkspaceInfoBar(
        modifier = modifier,
        selectedCount = selectedCount,
        onClearSelection = onClearSelection,
        leadingContent = {
            if (selectedCount == 0 && userAppsCount > 0) {
                InfoChip(
                    icon = Icons.TwoTone.Person,
                    label = pluralStringResource(R.plurals.apps_infobar_user_apps_count, userAppsCount, userAppsCount),
                )
            }

            if (selectedCount == 0 && systemAppsCount > 0) {
                InfoChip(
                    icon = Icons.TwoTone.Android,
                    label = pluralStringResource(
                        R.plurals.apps_infobar_system_apps_count,
                        systemAppsCount,
                        systemAppsCount
                    ),
                )
            }
        },
        trailingContent = {
            Spacer(modifier = Modifier.weight(1f))
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsInfoBarPreview() {
    AppsInfoBar(
        userAppsCount = 25,
        systemAppsCount = 142,
        selectedCount = 0,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsInfoBarWithSelectionPreview() {
    AppsInfoBar(
        userAppsCount = 25,
        systemAppsCount = 142,
        selectedCount = 5,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsInfoBarEmptyPreview() {
    AppsInfoBar(
        userAppsCount = 0,
        systemAppsCount = 0,
        selectedCount = 0,
    )
}
