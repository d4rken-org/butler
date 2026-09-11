package eu.darken.butler.workspace.ui.template

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.label
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

/** Singleton types are excluded: a second create of one returns AlreadyOpen rather than a new tab. */
fun List<WorkspaceTemplate>.newTabCandidates(): List<WorkspaceTemplate> = filterNot { it.type.isSingleton }

/** The template a new tab opens as, or null to let the user pick via the templates workspace. */
fun List<WorkspaceTemplate>.newTabTemplate(stored: Workspace.Type): WorkspaceTemplate? =
    if (stored == Workspace.Type.TEMPLATES) null else newTabCandidates().firstOrNull { it.type == stored }

/**
 * The name the new tab picker offered for a type, so every "new tab type" surface repeats the
 * wording the user chose from. Types without a template (TEMPLATES itself, singletons) keep the
 * generic workspace label.
 */
fun List<WorkspaceTemplate>.newTabTypeName(stored: Workspace.Type): CaString =
    newTabTemplate(stored)?.title ?: stored.label
