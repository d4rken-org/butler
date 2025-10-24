package eu.darken.butler.workspace.ui.manager.preview

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.preview.WorkspacePreviewRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages automatic preview capture and cleanup for workspace lifecycle events.
 *
 * This manager:
 * 1. Captures preview after workspace creation (with 2-3 second delay)
 * 2. Cleans up preview files when workspace closes
 *
 * All captures are performed offscreen via [WorkspacePreviewCaptureService].
 */
@Singleton
class WorkspacePreviewManager @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val workspaceRemote: WorkspaceRepo,
    private val captureService: WorkspacePreviewCaptureService,
    private val previewRepo: WorkspacePreviewRepo,
) {
    private val tag = logTag("Workspace", "PreviewManager")

    // Performance optimization: limit concurrent captures to avoid resource exhaustion
    private val captureSemaphore = Semaphore(2) // Max 2 concurrent captures

    // Track pending capture jobs to allow cancellation if workspace closes early
    private val pendingCaptures = ConcurrentHashMap<Workspace.Id, Job>()

    // Track last capture start time to implement staggering
    private var lastCaptureStartTime = 0L
    private val minimumStaggerDelayMs = 500L

    init {
        // Start listening to workspace events
        appScope.launch {
            log(tag, INFO) { "WorkspacePreviewManager initialized - listening to workspace events" }
            workspaceRemote.events.collect { event ->
                handleWorkspaceEvent(event)
            }
        }
    }

    private fun handleWorkspaceEvent(event: WorkspaceEvent) {
        when (event) {
            is WorkspaceEvent.Created -> {
                log(tag, INFO) { "Workspace created: ${event.workspaceId.shortTag}" }
                scheduleInitialCapture(event.workspaceId)
            }

            is WorkspaceEvent.Closed -> {
                log(tag, INFO) { "Workspace closed: ${event.workspaceId.shortTag}" }
                // Cancel any pending capture for this workspace
                pendingCaptures.remove(event.workspaceId)?.cancel()
                scheduleCleanup(event.workspaceId)
            }

            is WorkspaceEvent.AllClosed -> {
                log(tag, INFO) { "All workspaces closed - cleaning up all previews" }
                // Cancel all pending captures
                pendingCaptures.values.forEach { it.cancel() }
                pendingCaptures.clear()
                scheduleAllCleanup()
            }

            else -> {
                // Ignore other events
            }
        }
    }

    /**
     * Schedule initial preview capture after workspace creation.
     * Delayed by 2-3 seconds to allow workspace initialization.
     * Uses concurrency limiting and staggering for performance.
     */
    private fun scheduleInitialCapture(workspaceId: Workspace.Id) {
        val job = appScope.launch {
            try {
                // Wait for workspace to initialize
                log(tag) { "Scheduling initial capture for ${workspaceId.shortTag} (2.5s delay)" }
                delay(2500)

                // Get workspace info from state
                val currentState = workspaceRemote.state.first()
                val info = currentState.infos.find { it.id == workspaceId }
                if (info == null) {
                    log(tag, WARN) { "Workspace ${workspaceId.shortTag} no longer exists - skipping capture" }
                    return@launch
                }

                // Implement staggering: wait if a capture started recently
                val timeSinceLastCapture = System.currentTimeMillis() - lastCaptureStartTime
                if (timeSinceLastCapture < minimumStaggerDelayMs) {
                    val staggerDelay = minimumStaggerDelayMs - timeSinceLastCapture
                    log(tag) { "Staggering capture for ${workspaceId.shortTag} by ${staggerDelay}ms" }
                    delay(staggerDelay)
                }

                // Acquire semaphore to limit concurrent captures
                log(tag) { "Waiting for capture slot for ${workspaceId.shortTag}" }
                captureSemaphore.acquire()
                lastCaptureStartTime = System.currentTimeMillis()

                try {
                    log(tag, INFO) { "Capturing initial preview for ${workspaceId.shortTag} (${info.type})" }

                    val result = captureService.captureWorkspace(
                        workspaceId = workspaceId,
                        workspaceType = info.type
                    )

                    if (result != null) {
                        log(tag, INFO) { "✓ Successfully captured initial preview for ${workspaceId.shortTag}" }
                    } else {
                        log(tag, WARN) { "✗ Failed to capture initial preview for ${workspaceId.shortTag}" }
                    }
                } finally {
                    // Always release the semaphore
                    captureSemaphore.release()
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Error capturing initial preview for ${workspaceId.shortTag}: ${e.asLog()}" }
            } finally {
                // Remove from pending captures when done (or cancelled)
                pendingCaptures.remove(workspaceId)
            }
        }

        // Track the job so it can be cancelled if workspace closes early
        pendingCaptures[workspaceId] = job
    }

    /**
     * Schedule cleanup of preview file for closed workspace.
     */
    private fun scheduleCleanup(workspaceId: Workspace.Id) {
        appScope.launch(dispatcherProvider.IO) {
            try {
                log(tag) { "Cleaning up preview for ${workspaceId.shortTag}" }
                val deleted = previewRepo.deletePreview(workspaceId)
                if (deleted) {
                    log(tag, INFO) { "✓ Successfully deleted preview for ${workspaceId.shortTag}" }
                } else {
                    log(tag) { "No preview file to delete for ${workspaceId.shortTag}" }
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Error deleting preview for ${workspaceId.shortTag}: ${e.asLog()}" }
            }
        }
    }

    /**
     * Schedule cleanup of all preview files.
     */
    private fun scheduleAllCleanup() {
        appScope.launch(dispatcherProvider.IO) {
            try {
                log(tag) { "Cleaning up all previews" }
                val count = previewRepo.clearAllPreviews()
                log(tag, INFO) { "✓ Successfully deleted $count preview file(s)" }
            } catch (e: Exception) {
                log(tag, ERROR) { "Error deleting all previews: ${e.asLog()}" }
            }
        }
    }
}
