package eu.darken.butler.workspace.core.session

import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsArguments
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.templates.core.TemplatesWorkspace
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level implementation that extracts current state from workspaces to create arguments for restoration
 */
@Singleton
class AppWorkspaceStateExtractor @Inject constructor(
    private val baseExtractor: WorkspaceStateExtractor, // Delegate to base for unknown types
) {
    private val tag = logTag("Workspace", "AppStateExtractor")

    /**
     * Extract current state from a workspace and return it as Arguments
     * This allows us to restore workspaces with their current state rather than initial arguments
     */
    suspend fun extractArguments(workspace: Workspace): Workspace.Arguments? {
        return try {
            when (workspace) {
                is ExplorerWorkspace -> {
                    // Get current location from Explorer state
                    val state = workspace.state.first()
                    val currentLocation = state.currentLocation

                    when (currentLocation) {
                        is ExplorerLocation.Directory -> {
                            log(tag, DEBUG) { "Extracting Explorer path: ${currentLocation.path}" }
                            ExplorerWorkspace.Arguments(
                                startPath = currentLocation.path
                            )
                        }
                        else -> {
                            log(tag, DEBUG) { "Explorer at non-directory location, using default" }
                            ExplorerWorkspace.Arguments()
                        }
                    }
                }

                is SearcherWorkspace -> {
                    // Searcher workspace - keep default arguments
                    log(tag, DEBUG) { "Extracting Searcher with default arguments" }
                    SearcherWorkspace.Arguments(startTargets = null)
                }

                is EditorWorkspace -> {
                    // Get file path from editor if available
                    val filePath = workspace.filePath

                    if (filePath != null) {
                        log(tag, DEBUG) { "Extracting Editor file: ${filePath.name}" }
                        EditorWorkspace.Arguments(filePath = filePath)
                    } else {
                        log(tag, DEBUG) { "Editor has no file open" }
                        null // Don't restore editor without a file
                    }
                }

                is AppsWorkspace -> {
                    // Apps workspace doesn't need state extraction
                    AppsWorkspace.Arguments()
                }

                is AppDetailsWorkspace -> {
                    // Get current app details
                    val state = workspace.state.first()
                    val packageName = state.app?.packageName

                    if (packageName != null) {
                        log(tag, DEBUG) { "Extracting AppDetails package: $packageName" }
                        AppDetailsArguments(packageName = packageName)
                    } else {
                        log(tag, DEBUG) { "AppDetails has no package selected" }
                        null // Don't restore without a package
                    }
                }

                is TemplatesWorkspace -> {
                    // Templates workspace doesn't need state extraction
                    TemplatesWorkspace.Arguments(placeholder = "")
                }

                else -> {
                    log(tag, DEBUG) { "Unknown workspace type: ${workspace::class.simpleName}, delegating to base" }
                    baseExtractor.extractArguments(workspace)
                }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to extract arguments from ${workspace.type}: ${e.asLog()}" }
            baseExtractor.extractArguments(workspace)
        }
    }
}