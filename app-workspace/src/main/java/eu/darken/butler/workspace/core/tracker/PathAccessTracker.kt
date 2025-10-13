package eu.darken.butler.workspace.core.tracker

import eu.darken.butler.common.files.APath

interface PathAccessTracker {
    suspend fun trackPathAccess(path: APath<*>)
}