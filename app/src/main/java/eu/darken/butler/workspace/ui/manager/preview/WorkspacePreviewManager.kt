package eu.darken.butler.workspace.ui.manager.preview

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.Options
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import eu.darken.butler.workspace.ui.WorkspacePageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service that automatically invalidates workspace preview caches when workspaces gain focus.
 *
 * When a workspace is focused (opened/switched to), its preview cache is invalidated.
 * This ensures that the next time the workspace manager is opened, Coil will fetch
 * a fresh preview showing the current state of the workspace.
 *
 * Only operates when live preview is enabled in settings.
 */
@Singleton
class WorkspacePreviewManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val workspacePageManager: WorkspacePageManager,
    private val workspaceSettings: WorkspaceSettings,
    private val imageLoader: ImageLoader,
    private val workspacePreviewKeyer: WorkspacePreviewKeyer,
) {

    init {
        log(TAG) { "WorkspacePreviewRefreshManager initialized" }
    }

    fun start() {
        log(TAG, INFO) { "WorkspacePreviewRefreshManager started" }

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

        if (livePreviewEnabled && focusedId != null) {
            invalidatePreviewCache(focusedId)
        }
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

    companion object {
        private val TAG = logTag("Workspace", "Preview", "Manager")
    }
}
