package eu.darken.butler.main.core.operations.fgs

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot, just-in-time request to ask for POST_NOTIFICATIONS the first time a background
 * operation runs while notifications are disabled. [MainActivity] collects [prompts] and launches
 * the runtime permission request. Non-blocking: the operation runs regardless of the outcome.
 */
@Singleton
class NotificationPermissionGate @Inject constructor() {

    // No replay: the prompt is a one-shot signal. Replaying it would re-launch the permission
    // request on every repeatOnLifecycle restart (each app foreground). The buffer lets the single
    // emit succeed even if the collector is momentarily inactive; it is delivered at most once.
    private val _prompts = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val prompts: SharedFlow<Unit> = _prompts.asSharedFlow()

    private val requested = AtomicBoolean(false)

    fun maybeRequest() {
        if (requested.compareAndSet(false, true)) _prompts.tryEmit(Unit)
    }
}
