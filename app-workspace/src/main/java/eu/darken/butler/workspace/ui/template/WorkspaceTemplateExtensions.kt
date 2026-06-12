package eu.darken.butler.workspace.ui.template

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

fun Collection<WorkspaceTemplate>.availableTemplates(): Flow<List<WorkspaceTemplate>> =
    if (isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(
            map { template ->
                template.availability
                    .catch { emit(false) }
                    .distinctUntilChanged()
                    .map { enabled -> template.takeIf { enabled } }
            }
        ) { templates ->
            templates
                .filterNotNull()
                .sortedWith(compareBy<WorkspaceTemplate> { it.sortOrder }.thenBy { it.type.ordinal })
        }
    }
