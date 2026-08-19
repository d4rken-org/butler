package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

/**
 * Builds the initial [Workspace.Info] used to seed [stateInWorkspace].
 *
 * The static relationship fields ([Workspace.Info.callerWorkspaceId],
 * [Workspace.Info.modalPresentation], [Workspace.Info.pausableAsChild],
 * [Workspace.Info.contentPath]) are extracted from [arguments] so they are correct before the first
 * real emission — WorkspaceRepo reads them synchronously for lifecycle decisions (child cleanup,
 * sub-workspace limit exclusion, unit pausing, per-path open dedup).
 *
 * [title] and [subtitle] should come from the same derivation the type's
 * [WorkspaceFactory.deriveDisplay] uses, so a paused stand-in and the live workspace show the
 * same identity for the same arguments.
 */
fun Workspace<*>.initialInfo(
    title: CaString,
    arguments: Workspace.Arguments,
    subtitle: CaString? = null,
): Workspace.Info {
    val withCaller = arguments as? Workspace.ArgumentsWithCaller
    return Workspace.Info(
        id = id,
        type = type,
        title = title,
        subtitle = subtitle,
        callerWorkspaceId = withCaller?.callerWorkspaceId,
        modalPresentation = withCaller?.modalPresentation ?: Workspace.ModalPresentationMode.PANE_LOCAL,
        pausableAsChild = arguments.isPausableAsChild,
        contentPath = (arguments as? Workspace.ArgumentsWithContentPath)?.contentPath,
        isPersistable = arguments.isPersistable,
    )
}

/**
 * Converts a workspace's info flow into the [StateFlow] required by [Workspace.info].
 *
 * Uses [SharingStarted.Eagerly], NOT `shareLatest`/`replayingShare`: those use
 * `replayExpiration = 0`, which resets [StateFlow.value] back to [initial] whenever the last
 * subscriber leaves — synchronous readers would then see stale seed data. Eagerly keeps the
 * upstream running for the workspace's lifetime; the scope is cancelled in `release()`.
 *
 * Upstream errors are mapped to [Workspace.LifecycleState.Error] instead of killing the
 * sharing coroutine (which would freeze [StateFlow.value] silently).
 */
fun Flow<Workspace.Info>.stateInWorkspace(
    scope: CoroutineScope,
    initial: Workspace.Info,
): StateFlow<Workspace.Info> = this
    .catch { error ->
        log(tag, ERROR) { "Workspace info flow failed for ${initial.id}: ${error.asLog()}" }
        emit(initial.copy(lifecycleState = Workspace.LifecycleState.Error(error)))
    }
    .stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = initial,
    )

private val tag = logTag("Workspace", "Info")
