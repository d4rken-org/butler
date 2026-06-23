package eu.darken.butler.main.core.operations.fgs

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.withStateUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * App-scoped owner of the operation foreground service and its notifications.
 *
 * Notifications are shown ONLY while the app is in the background: an on-screen app already shows
 * progress in its in-app operations bar, and a foregrounded app's process is already protected from
 * being killed. The foreground service is therefore started when the app leaves the foreground with
 * an active operation (the legal window — driven by [MainActivity]'s `onUserLeaveHint` and
 * [ProcessLifecycleOwner]) and torn down when the app returns or operations drain.
 *
 * Single-writer: all state mutations happen under [mutex]; the synchronous Android service callbacks
 * touch only volatiles.
 */
@Singleton
class OperationFgsCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val operationsManager: OperationsManager,
    private val notifications: OperationNotifications,
    private val notificationManager: NotificationManager,
    private val permissionGate: NotificationPermissionGate,
) {

    private class OpSlot(val progressId: Int, val attentionId: Int)

    private val mutex = Mutex()
    private val idCounter = AtomicInteger(NOTIFICATION_ID_SUMMARY + 1)
    private val slots = mutableMapOf<Operation.Id, OpSlot>()
    // Op id -> dismissible failure notification id. Cleared when the op leaves OperationsManager
    // (user taps "Clear completed") or when the app returns to the foreground.
    private val failureNotifications = mutableMapOf<Operation.Id, Int>()

    @Volatile private var appForeground = true
    // AtomicBoolean (not a plain volatile) so the synchronous foreground-eligible start path in
    // onAppBackgrounded() and the mutex-guarded reconcile() can't both win the start latch.
    private val serviceRequested = AtomicBoolean(false)
    // Set after a dataSync FGS timeout: the OS budget won't reset until the app foregrounds or 24h
    // pass, so we stop re-acquiring the FGS until operations drain.
    @Volatile private var fgsSuppressed = false
    @Volatile private var latestSummary: android.app.Notification? = null
    // Volatile so the synchronous start path may read them off-mutex; written under the mutex.
    @Volatile private var lastOngoing: List<ManagedOperation> = emptyList()
    @Volatile private var lastOngoingEmpty = true
    private var stopJob: Job? = null

    fun start() {
        log(TAG, INFO) { "start()" }
        notifications.setupChannels()
        clearStaleNotifications()

        operationsManager.operations
            .withStateUpdates()
            .onEach { handleOperations(it) }
            .launchIn(appScope)

        operationsManager.completedOperations
            .onEach { handleCompletion(it) }
            .launchIn(appScope)
    }

    /** The user explicitly left the app (Home/Recents). Called while still foreground-eligible, so
     *  this is the legal moment to acquire the foreground service. */
    fun onAppBackgrounded() {
        if (!appForeground) return
        log(TAG) { "onAppBackgrounded()" }
        appForeground = false
        // Acquire the FGS synchronously on THIS (foreground-eligible) thread — deferring it to the
        // appScope dispatcher risks the process no longer being foreground-eligible by the time it
        // runs, which Android 12+ rejects. The launched reconcile then posts the notifications.
        if (!lastOngoingEmpty && !fgsSuppressed) {
            latestSummary = notifications.buildSummary(lastOngoing.size)
            ensureServiceStarted()
        }
        appScope.launch { mutex.withLock { reconcile(lastOngoing) } }
    }

    fun onAppForegrounded() {
        if (appForeground) return
        log(TAG) { "onAppForegrounded()" }
        appForeground = true
        appScope.launch {
            mutex.withLock {
                // Back on-screen: drop all system notifications; the in-app bar takes over.
                failureNotifications.values.forEach { notificationManager.cancel(it) }
                failureNotifications.clear()
                reconcile(lastOngoing)
            }
        }
    }

    private suspend fun handleOperations(all: List<ManagedOperation>) = mutex.withLock {
        // A failed op's notification is dismissed when the op leaves OperationsManager — e.g. the
        // user taps "Clear completed" or dismisses the result in the in-app operations bar.
        if (failureNotifications.isNotEmpty()) {
            val present = all.mapTo(HashSet(all.size)) { it.id }
            (failureNotifications.keys - present).forEach { id ->
                failureNotifications.remove(id)?.let { notificationManager.cancel(it) }
            }
        }
        reconcile(all.filter { it.state.value !is Operation.State.Completed })
    }

    private suspend fun handleCompletion(snapshot: CompletedOperationSnapshot) = mutex.withLock {
        val error = snapshot.state.error
        if (error == null || error is CancellationException) return@withLock
        // Foreground failures are visible in the in-app bar; only notify when the user is away.
        if (appForeground) return@withLock
        log(TAG, INFO) { "Posting failure notification for ${snapshot.id}" }
        val id = nextId()
        failureNotifications[snapshot.id] = id
        notificationManager.notify(id, notifications.buildFailure(id, snapshot))
    }

    /** Caller holds [mutex]. Brings the FGS + notifications in line with (ongoing ops, app state). */
    private fun reconcile(ongoing: List<ManagedOperation>) {
        lastOngoing = ongoing
        lastOngoingEmpty = ongoing.isEmpty()

        // Notifications/FGS only while backgrounded with active work.
        if (appForeground || ongoing.isEmpty()) {
            if (ongoing.isEmpty()) {
                scheduleStop()
            } else {
                // Foreground with active ops → tear down immediately (in-app bar shows progress).
                cancelStop()
                doStop()
            }
            return
        }
        cancelStop()

        latestSummary = notifications.buildSummary(ongoing.size)
        ensureServiceStarted()
        // Post the summary unconditionally (even before startForeground adopts the same id) so the
        // grouped per-op children below are never shown without their group summary.
        notificationManager.notify(NOTIFICATION_ID_SUMMARY, latestSummary!!)

        val seen = HashSet<Operation.Id>(ongoing.size)
        for (op in ongoing) {
            seen += op.id
            val slot = slots.getOrPut(op.id) { OpSlot(nextId(), nextId()) }
            when (op.state.value) {
                is Operation.State.Waiting -> {
                    notificationManager.notify(slot.attentionId, notifications.buildAttention(slot.attentionId, op))
                    notificationManager.cancel(slot.progressId)
                }
                else -> {
                    notificationManager.notify(
                        slot.progressId,
                        notifications.buildProgress(slot.progressId, op, op.state.value),
                    )
                    notificationManager.cancel(slot.attentionId)
                }
            }
        }

        // Drop notifications for operations that completed or were removed (silent success/cancel).
        val gone = slots.keys - seen
        for (id in gone) {
            slots.remove(id)?.let {
                notificationManager.cancel(it.progressId)
                notificationManager.cancel(it.attentionId)
            }
        }
    }

    // --- Service lifecycle callbacks (invoked on the service's main thread, NOT in a coroutine) ---

    fun onServiceStarted(service: Service) {
        val summary = latestSummary ?: notifications.buildSummary(1)
        log(TAG) { "onServiceStarted(): startForeground" }
        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID_SUMMARY,
            summary,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        // Quick-op race / returned-to-foreground race: tear down if we no longer need the FGS.
        appScope.launch {
            mutex.withLock { if (lastOngoingEmpty || appForeground) doStop() }
        }
    }

    fun onServiceTimeout(service: Service) {
        // dataSync budget exhausted; release foreground but leave operations running unprotected.
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        service.stopSelf()
        appScope.launch {
            mutex.withLock {
                serviceRequested.set(false)
                // Don't try to re-acquire the FGS until operations drain (doStop clears this).
                fgsSuppressed = true
                clearAllOpNotifications()
            }
        }
    }

    fun onServiceDestroyed() {
        log(TAG) { "onServiceDestroyed()" }
        // Reset so a concurrent reconcile()/doStop() restart decision sees the service as gone.
        serviceRequested.set(false)
    }

    // --- internals ---

    /** May be called either from a mutex-guarded reconcile() or synchronously from
     *  onAppBackgrounded(); the [serviceRequested] CAS ensures only one caller starts the service. */
    private fun ensureServiceStarted() {
        if (fgsSuppressed) return
        if (!serviceRequested.compareAndSet(false, true)) return
        log(TAG, INFO) { "Starting foreground service" }
        try {
            context.startForegroundService(Intent(context, OperationFgsService::class.java))
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException if the background transition was missed
            // (e.g. screen-off rather than Home). The operation keeps running, just unprotected.
            log(TAG, WARN) { "startForegroundService failed: ${e.asLog()}" }
            serviceRequested.set(false)
        }
        if (hasApiLevel(33) && !notificationManager.areNotificationsEnabled()) {
            log(TAG, WARN) { "Notifications disabled — progress/conflict notifications won't be visible" }
            permissionGate.maybeRequest()
        }
    }

    private fun scheduleStop() {
        if (stopJob?.isActive == true) return
        stopJob = appScope.launch {
            delay(STOP_DEBOUNCE)
            mutex.withLock { if (lastOngoingEmpty) doStop() }
        }
    }

    private fun cancelStop() {
        stopJob?.cancel()
        stopJob = null
    }

    private fun doStop() {
        log(TAG, INFO) { "doStop()" }
        clearAllOpNotifications()
        if (serviceRequested.getAndSet(false)) {
            context.stopService(Intent(context, OperationFgsService::class.java))
        }
        notificationManager.cancel(NOTIFICATION_ID_SUMMARY)
        // A fresh background batch may acquire the FGS again.
        fgsSuppressed = false
    }

    private fun clearAllOpNotifications() {
        slots.values.forEach {
            notificationManager.cancel(it.progressId)
            notificationManager.cancel(it.attentionId)
        }
        slots.clear()
    }

    // Counter starts above NOTIFICATION_ID_SUMMARY and only increments, so it never collides with it.
    private fun nextId(): Int = idCounter.getAndIncrement()

    /** Cancel leftover operation notifications from a previous process (they survive process death). */
    private fun clearStaleNotifications() {
        try {
            notificationManager.cancel(NOTIFICATION_ID_SUMMARY)
            notificationManager.activeNotifications
                .filter {
                    val ch = it.notification.channelId
                    ch == NOTIFICATION_CHANNEL_PROGRESS || ch == NOTIFICATION_CHANNEL_ATTENTION
                }
                .forEach { notificationManager.cancel(it.id) }
        } catch (e: Exception) {
            log(TAG, WARN) { "clearStaleNotifications failed: ${e.asLog()}" }
        }
    }

    companion object {
        private val TAG = logTag("Operations", "FGS", "Coordinator")
        private val STOP_DEBOUNCE = 2.seconds
    }
}
