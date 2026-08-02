package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

/**
 * Sort options for the current location.
 *
 * Opens on the rule this folder owns when there is one, otherwise on "All folders" with the tab
 * checkbox clear - so an untouched sheet behaves exactly as it did before per-folder rules existed
 * and casual re-sorting never creates one.
 */
@Composable
fun SortOptionsSheet(
    state: ExplorerDialogState.EditSortOptions,
    onDismiss: () -> Unit,
    onConfirm: (SortOptionsResult) -> Unit,
    onClearTabOverrides: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        modifier = modifier,
    ) {
        SortOptionsContent(
            state = state,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
            onClearTabOverrides = onClearTabOverrides,
        )
    }
}

@Composable
private fun SortOptionsContent(
    state: ExplorerDialogState.EditSortOptions,
    onDismiss: () -> Unit,
    onConfirm: (SortOptionsResult) -> Unit,
    onClearTabOverrides: () -> Unit,
) {
    val context = LocalContext.current
    var selectedMode by remember { mutableStateOf(state.currentSortSettings.mode) }
    var isReversed by remember { mutableStateOf(state.currentSortSettings.reversed) }
    var selectedScope by remember { mutableStateOf(state.scope) }
    var onlyThisTab by remember { mutableStateOf(state.onlyThisTab) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.explorer_action_sort),
            style = MaterialTheme.typography.titleLarge,
        )

        Text(
            text = stringResource(R.string.explorer_sort_mode_label),
            style = MaterialTheme.typography.titleSmall,
        )

        Column(modifier = Modifier.selectableGroup()) {
            SortSettings.Mode.entries.forEach { mode ->
                OptionRow(
                    label = stringResource(sortModeLabel(mode)),
                    selected = selectedMode == mode,
                    onClick = { selectedMode = mode },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.explorer_sort_descending_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = isReversed,
                onCheckedChange = { isReversed = it },
            )
        }

        if (state.isDirectory) {
            HorizontalDivider()

            Text(
                text = stringResource(R.string.explorer_sort_scope_label),
                style = MaterialTheme.typography.titleSmall,
            )

            Column(modifier = Modifier.selectableGroup()) {
                scopeOptions(state.canUseDefaultHere).forEach { scope ->
                    OptionRow(
                        label = stringResource(scopeLabel(scope)),
                        selected = selectedScope == scope,
                        onClick = { selectedScope = scope },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = onlyThisTab,
                        onValueChange = { onlyThisTab = it },
                        role = Role.Checkbox,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = onlyThisTab, onCheckedChange = null)
                Text(
                    text = stringResource(R.string.explorer_sort_only_this_tab_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            state.inheritedFrom?.let { inheritedFrom ->
                Notice(text = stringResource(R.string.explorer_sort_inherited_notice, inheritedFrom.get(context)))
            }

            state.suppressedAncestor?.let { suppressed ->
                Notice(text = stringResource(R.string.explorer_sort_suppressed_notice, suppressed.get(context)))
            }
        }

        if (state.hasTabDefault || state.tabRuleCount > 0) {
            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        state.tabRuleCount > 0 -> pluralStringResource(
                            R.plurals.explorer_sort_tab_overrides_notice,
                            state.tabRuleCount,
                            state.tabRuleCount,
                        )
                        else -> stringResource(R.string.explorer_sort_tab_overrides_default_notice)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearTabOverrides) {
                    Text(stringResource(R.string.explorer_sort_tab_overrides_clear_action))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                onClick = {
                    onConfirm(
                        SortOptionsResult(
                            sortSettings = SortSettings(mode = selectedMode, reversed = isReversed),
                            scope = if (state.isDirectory) selectedScope else SortScope.ALL_FOLDERS,
                            onlyThisTab = state.isDirectory && onlyThisTab,
                        )
                    )
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun Notice(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

/** "Use default here" only makes sense where there is something to suppress. */
private fun scopeOptions(canUseDefaultHere: Boolean): List<SortScope> = SortScope.entries
    .filter { it != SortScope.USE_DEFAULT_HERE || canUseDefaultHere }

private fun scopeLabel(scope: SortScope): Int = when (scope) {
    SortScope.ALL_FOLDERS -> R.string.explorer_sort_scope_all_folders
    SortScope.THIS_FOLDER -> R.string.explorer_sort_scope_this_folder
    SortScope.THIS_FOLDER_AND_SUBFOLDERS -> R.string.explorer_sort_scope_this_folder_and_subfolders
    SortScope.USE_DEFAULT_HERE -> R.string.explorer_sort_scope_use_default_here
}

private fun sortModeLabel(mode: SortSettings.Mode): Int = when (mode) {
    SortSettings.Mode.NAME -> R.string.explorer_sort_mode_name_label
    SortSettings.Mode.SIZE -> R.string.explorer_sort_mode_size_label
    SortSettings.Mode.MODIFIED_AT -> R.string.explorer_sort_mode_modified_label
    SortSettings.Mode.CREATED_AT -> R.string.explorer_sort_mode_created_label
}

private fun previewState(
    isDirectory: Boolean = true,
    scope: SortScope = SortScope.ALL_FOLDERS,
    onlyThisTab: Boolean = false,
    canUseDefaultHere: Boolean = false,
    inheritedFrom: CaString? = null,
    suppressedAncestor: CaString? = null,
    hasTabDefault: Boolean = false,
    tabRuleCount: Int = 0,
) = ExplorerDialogState.EditSortOptions(
    currentSortSettings = SortSettings(mode = SortSettings.Mode.NAME, reversed = false),
    isDirectory = isDirectory,
    scope = scope,
    onlyThisTab = onlyThisTab,
    canUseDefaultHere = canUseDefaultHere,
    inheritedFrom = inheritedFrom,
    suppressedAncestor = suppressedAncestor,
    hasTabDefault = hasTabDefault,
    tabRuleCount = tabRuleCount,
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SortOptionsSheetNoRulePreview() {
    SortOptionsSheet(
        state = previewState(),
        onDismiss = {},
        onConfirm = {},
        onClearTabOverrides = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SortOptionsSheetOwnsRulePreview() {
    SortOptionsSheet(
        state = previewState(
            scope = SortScope.THIS_FOLDER_AND_SUBFOLDERS,
            suppressedAncestor = "/storage/emulated/0/Download".toCaString(),
        ),
        onDismiss = {},
        onConfirm = {},
        onClearTabOverrides = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SortOptionsSheetInheritsPreview() {
    SortOptionsSheet(
        state = previewState(
            canUseDefaultHere = true,
            inheritedFrom = "/storage/emulated/0/Download".toCaString(),
        ),
        onDismiss = {},
        onConfirm = {},
        onClearTabOverrides = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SortOptionsSheetOwnsMarkerPreview() {
    SortOptionsSheet(
        state = previewState(
            scope = SortScope.USE_DEFAULT_HERE,
            canUseDefaultHere = true,
            suppressedAncestor = "/storage/emulated/0/Download".toCaString(),
        ),
        onDismiss = {},
        onConfirm = {},
        onClearTabOverrides = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SortOptionsSheetTabOverridesPreview() {
    SortOptionsSheet(
        state = previewState(
            scope = SortScope.THIS_FOLDER,
            onlyThisTab = true,
            hasTabDefault = true,
            tabRuleCount = 3,
        ),
        onDismiss = {},
        onConfirm = {},
        onClearTabOverrides = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SortOptionsSheetNonDirectoryPreview() {
    SortOptionsSheet(
        state = previewState(isDirectory = false),
        onDismiss = {},
        onConfirm = {},
        onClearTabOverrides = {},
    )
}
