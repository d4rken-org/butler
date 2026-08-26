package eu.darken.butler.workspace.ui.manager.preview

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.Options
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceStash
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.session.WorkspaceSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service that manages workspace preview caches.
 *
 * Responsibilities:
 * - Invalidates preview cache when workspaces gain focus (for live preview updates)
 * - Deletes preview cache when workspaces are closed (cleanup)
 *
 * When a workspace is focused (opened/switched to), its preview cache is invalidated
 * if live preview is enabled. This ensures that the workspace manager shows fresh
 * previews reflecting the current state of each workspace.
 *
 * When a workspace is closed, its preview cache is deleted to free up memory and disk space.
 */
@Singleton
class WorkspacePreviewManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val workspacePageManager: WorkspacePageManager,
    private val workspaceSettings: WorkspaceSettings,
    private val workspaceRepo: WorkspaceRepo,
    private val imageLoader: ImageLoader,
    private val workspacePreviewKeyer: WorkspacePreviewKeyer,
    private val sessionManager: WorkspaceSessionManager,
    private val closedStash: ClosedWorkspaceStash,
) {

    init {
        log(TAG) { "WorkspacePreviewRefreshManager initialized" }
    }

    fun start() {
        log(TAG, INFO) { "WorkspacePreviewRefreshManager started" }

        // Wait for session restoration before checking for orphaned previews.
        // Without this, we'd see an empty workspace list before restoration completes
        // and incorrectly clear all cached previews.
        sessionManager.state
            .filter { it != WorkspaceSessionManager.State.Restoring }
            .take(1)
            .flatMapLatest { workspaceRepo.state.map { it.infos.isEmpty() }.take(1) }
            .onEach { isEmpty ->
                if (isEmpty) {
                    log(TAG, INFO) { "No workspaces after restoration - clearing orphaned preview caches" }
                    clearAllPreviewCaches()
                }
            }
            .catch { log(TAG, ERROR) { "Failed to check for orphaned previews: ${it.asLog()}" } }
            .launchIn(appScope)

        // Invalidate preview cache when workspace gains focus (for live preview)
        combine(
            workspacePageManager.state
                .map { it.focusedWorkspaceId }
                .distinctUntilChanged(),
            workspaceSettings.livePreview.flow,
        ) { focusedId, livePreviewEnabled ->
            log(TAG) { "Focus changed: workspaceId=$focusedId, livePreviewEnabled=$livePreviewEnabled" }

            if (livePreviewEnabled && focusedId != null) {
                invalidatePreviewCache(focusedId)
            }
        }
            .catch { log(TAG, ERROR) { "Failed to invalidate preview cache: ${it.asLog()}" } }
            .launchIn(appScope)

        // Delete preview cache when workspace is closed
        workspaceRepo.events
            .onEach { event ->
                when (event) {
                    is WorkspaceEvent.Closed -> {
                        // Checked as late as possible: an undo can restore the workspace while this
                        // event is in flight, and dropping its thumbnail then leaves the manager
                        // showing a placeholder until the next capture.
                        if (closedStash.currentTokenOf(event.workspaceId) != null) {
                            log(TAG, INFO) { "Workspace ${event.workspaceId.shortTag} is back, keeping its preview" }
                        } else {
                            log(TAG, INFO) { "Deleting preview for closed workspace ${event.workspaceId.shortTag}" }
                            invalidatePreviewCache(event.workspaceId)
                        }
                    }
                    is WorkspaceEvent.AllClosed -> {
                        log(TAG, INFO) { "All workspaces closed - clearing all preview caches" }
                        clearAllPreviewCaches()
                    }
                    else -> {
                        // Ignore other events
                    }
                }
            }
            .catch { log(TAG, ERROR) { "Failed to process workspace event: ${it.asLog()}" } }
            .launchIn(appScope)
    }

    /**
     * Invalidates the preview cache for the currently focused workspace.
     *
     * This should be called when the workspace manager is opened to ensure
     * that previews reflect the current state, even if the workspace hasn't
     * lost/regained focus since the last manager open.
     */
    suspend fun invalidateFocusedWorkspacePreview() {
        val focusedId = workspacePageManager.state.value.focusedWorkspaceId
        val livePreviewEnabled = workspaceSettings.livePreview.value()

        log(TAG) { "Manual invalidation requested: workspaceId=$focusedId, livePreviewEnabled=$livePreviewEnabled" }

        if (!livePreviewEnabled || focusedId == null) return

        // The manager previews what is on top of the focused tab, which is not always the focused
        // workspace - launching a picker never moves global focus. Scoped to the focused unit on
        // purpose: every unit's top would re-capture every stacked tab on each manager open, and a
        // capture is a full offscreen page composition.
        // peekStacks, not the shared state flow: that flow's replay cache can lag a swap, and a stale
        // topology invalidates the wrong top id - leaving exactly the stale thumbnail this prevents.
        val stacks = workspaceRepo.peekStacks()
        val topId = stacks.topChainByRoot(focusedId)[stacks.ownerOf(focusedId)]?.leaf?.id
        setOfNotNull(focusedId, topId).forEach { invalidatePreviewCache(it) }
    }

    private fun invalidatePreviewCache(workspaceId: Workspace.Id) {
        log(TAG, INFO) { "Invalidating preview cache for workspace ${workspaceId.shortTag}" }

        val cacheKey = workspacePreviewKeyer.key(WorkspacePreviewModel(workspaceId), Options(context))
        log(TAG) { "Removing cache key: $cacheKey" }

        imageLoader.memoryCache?.run {
            val success = remove(MemoryCache.Key(cacheKey))
            log(TAG, VERBOSE) { "Removed from memoryCache=$success $cacheKey" }
        } ?: log(TAG, VERBOSE) { "Memorycache is not available." }

        imageLoader.diskCache?.run {

            val success = remove(cacheKey)
            log(TAG, VERBOSE) { "Removed from diskCache=$success $cacheKey" }
        } ?: log(TAG, VERBOSE) { "DiskCache is not available." }

    }

    private fun clearAllPreviewCaches() {
        log(TAG, INFO) { "Clearing all workspace preview caches" }

        imageLoader.memoryCache?.run {
            clear()
            log(TAG, VERBOSE) { "Cleared all memoryCache entries" }
        } ?: log(TAG, VERBOSE) { "Memorycache is not available." }

        imageLoader.diskCache?.run {
            clear()
            log(TAG, VERBOSE) { "Cleared all diskCache entries" }
        } ?: log(TAG, VERBOSE) { "DiskCache is not available." }
    }

    companion object {
        private val TAG = logTag("Workspace", "Preview", "Manager")
    }
}
