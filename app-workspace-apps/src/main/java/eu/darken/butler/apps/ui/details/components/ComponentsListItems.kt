package eu.darken.butler.apps.ui.details.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.core.details.components.ComponentsUiState
import eu.darken.butler.apps.core.details.components.filter
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Renders the full, grouped component list as flat lazy items so large apps (hundreds of
 * components) compose incrementally instead of all at once.
 *
 * Takes both the raw [state] and the already-[filtered] data because [LazyListScope] is not a
 * composable scope: filtering has to happen in the page, and only the raw state can tell "this
 * package has no components" apart from "the filter removed everything".
 */
fun LazyListScope.appComponentsItems(
    state: ComponentsUiState,
    filtered: ComponentsData,
    query: String,
    onComponentClick: (ComponentEntry) -> Unit,
) {
    when (state) {
        ComponentsUiState.Loading -> item(key = "components-loading") {
            LazyMessage(stringResource(R.string.apps_components_loading))
        }

        ComponentsUiState.Error -> item(key = "components-error") {
            LazyMessage(stringResource(R.string.apps_components_error))
        }

        is ComponentsUiState.Ready -> when {
            state.data.total == 0 -> item(key = "components-empty") {
                LazyMessage(stringResource(R.string.apps_components_empty))
            }

            filtered.total == 0 && query.isNotBlank() -> item(key = "components-no-matches") {
                LazyMessage(stringResource(R.string.apps_components_no_matches, query))
            }

            else -> {
                componentGroup("activities", R.string.apps_components_activities_label, filtered.activities, query, onComponentClick)
                componentGroup("services", R.string.apps_components_services_label, filtered.services, query, onComponentClick)
                componentGroup("receivers", R.string.apps_components_receivers_label, filtered.receivers, query, onComponentClick)
                componentGroup("providers", R.string.apps_components_providers_label, filtered.providers, query, onComponentClick)
            }
        }
    }
}

private fun LazyListScope.componentGroup(
    groupId: String,
    @StringRes titleRes: Int,
    entries: List<ComponentEntry>,
    query: String,
    onComponentClick: (ComponentEntry) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "header:$groupId") {
        ComponentGroupHeader(title = stringResource(titleRes), count = entries.size)
    }
    items(entries, key = { it.key }) { entry ->
        ComponentRow(
            entry = entry,
            query = query,
            onClick = { onComponentClick(entry) },
        )
    }
}

@Composable
private fun LazyMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

internal val previewComponentsData = ComponentsData(
    activities = listOf(
        ComponentEntry(
            kind = ComponentKind.ACTIVITY,
            packageName = "com.example.app",
            className = "com.example.app.MainActivity",
            isExported = true,
            enabledState = ComponentEnabledState.ENABLED,
        ),
        ComponentEntry(
            kind = ComponentKind.ACTIVITY,
            packageName = "com.example.app",
            className = "com.example.app.SettingsActivity",
            isExported = false,
            enabledState = ComponentEnabledState.ENABLED,
        ),
    ),
    services = listOf(
        ComponentEntry(
            kind = ComponentKind.SERVICE,
            packageName = "com.example.app",
            className = "com.example.app.sync.SyncService",
            isExported = false,
            enabledState = ComponentEnabledState.ENABLED,
        ),
    ),
    receivers = listOf(
        ComponentEntry(
            kind = ComponentKind.RECEIVER,
            packageName = "com.example.app",
            className = "com.example.app.BootReceiver",
            isExported = true,
            enabledState = ComponentEnabledState.DISABLED,
        ),
    ),
    providers = listOf(
        ComponentEntry(
            kind = ComponentKind.PROVIDER,
            packageName = "com.example.app",
            className = "com.example.app.data.FileProvider",
            isExported = false,
            authority = "com.example.app.files",
            enabledState = ComponentEnabledState.ENABLED,
        ),
    ),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppComponentsItemsPreview() {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        appComponentsItems(
            state = ComponentsUiState.Ready(previewComponentsData),
            filtered = previewComponentsData,
            query = "",
            onComponentClick = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppComponentsItemsFilteredPreview() {
    val query = "sync"
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        appComponentsItems(
            state = ComponentsUiState.Ready(previewComponentsData),
            filtered = previewComponentsData.filter(query),
            query = query,
            onComponentClick = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppComponentsItemsNoMatchesPreview() {
    val query = "nothing"
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        appComponentsItems(
            state = ComponentsUiState.Ready(previewComponentsData),
            filtered = previewComponentsData.filter(query),
            query = query,
            onComponentClick = {},
        )
    }
}
