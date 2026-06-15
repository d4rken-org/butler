package eu.darken.butler.workspace.core

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-build sanity check for Hilt multibound workspace registrations.
 *
 * Dagger only catches *duplicate* map keys at compile time, not *missing* ones — a workspace
 * module that stops contributing (e.g. its dependency was removed from `:app`) would otherwise
 * only surface as a runtime crash when that workspace type is created or restored.
 */
@Singleton
class WorkspaceRegistryValidator @Inject constructor(
    private val factoryMap: Map<Workspace.Type, @JvmSuppressWildcards WorkspaceFactory<*>>,
    private val pageHostMap: Map<Workspace.Type, @JvmSuppressWildcards WorkspacePageHostEntry>,
    private val templates: Set<@JvmSuppressWildcards WorkspaceTemplate>,
) {

    fun validate() {
        val allTypes = Workspace.Type.entries.toSet()

        val missingFactories = allTypes - factoryMap.keys
        check(missingFactories.isEmpty()) {
            "Workspace types without a registered WorkspaceFactory: $missingFactories"
        }

        val missingPageHosts = allTypes - pageHostMap.keys
        check(missingPageHosts.isEmpty()) {
            "Workspace types without a registered WorkspacePageHostEntry: $missingPageHosts"
        }

        val duplicateTemplateTypes = templates.groupBy { it.type }.filterValues { it.size > 1 }.keys
        check(duplicateTemplateTypes.isEmpty()) {
            "Multiple WorkspaceTemplates registered for: $duplicateTemplateTypes"
        }

        val expectedTemplateTypes = setOf(
            Workspace.Type.EXPLORER,
            Workspace.Type.SEARCHER,
            Workspace.Type.EDITOR,
            Workspace.Type.APPS,
            Workspace.Type.HISTORY,
            Workspace.Type.DEVELOPER,
            Workspace.Type.BUG_REPORT,
        )
        val actualTemplateTypes = templates.map { it.type }.toSet()
        check(actualTemplateTypes == expectedTemplateTypes) {
            "Unexpected WorkspaceTemplate registrations. Expected $expectedTemplateTypes but was $actualTemplateTypes"
        }

        log(TAG) { "Workspace registry OK: ${factoryMap.size} factories, ${pageHostMap.size} page hosts, ${templates.size} templates" }
    }

    companion object {
        private val TAG = logTag("Workspace", "RegistryValidator")
    }
}
