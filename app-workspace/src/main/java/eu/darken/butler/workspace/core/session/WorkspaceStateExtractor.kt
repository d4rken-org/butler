package eu.darken.butler.workspace.core.session

import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts current state from workspaces to create arguments for restoration
 */
@Singleton
class WorkspaceStateExtractor @Inject constructor() {
    private val tag = logTag("Workspace", "StateExtractor")

    /**
     * Extract current state from a workspace and return it as Arguments
     * This allows us to restore workspaces with their current state rather than initial arguments
     *
     * Note: Individual workspace implementations should override this with their specific extraction logic.
     * This base implementation returns null to indicate no state extraction.
     */
    suspend fun extractArguments(workspace: Workspace): Workspace.Arguments? {
        return try {
            log(tag, DEBUG) { "Extracting arguments for ${workspace.type}" }
            // Generic extraction - workspaces can override this in their own managers
            null
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to extract arguments from ${workspace.type}: ${e.asLog()}" }
            null
        }
    }
}