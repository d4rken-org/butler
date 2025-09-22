package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OperationsRepo @Inject constructor() {
    fun clearWorkspaceById(id: Workspace.Id) {
        log(TAG, INFO) { "Clearing workspace $id" }
        TODO("Not yet implemented")
    }

    companion object {
        private val TAG = logTag("Workspace", "Operations", "Repo")
    }
}