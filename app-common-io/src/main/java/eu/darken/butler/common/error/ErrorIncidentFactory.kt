package eu.darken.butler.common.error

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.RingLogBuffer
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Freezes an error into an [ErrorIncident] at the moment it happens.
 *
 * Access-mode state is read from settings and from probe results those classes already hold. This
 * must never trigger a probe or a bind: building a report is not a reason to start a privileged
 * session.
 */
@Singleton
class ErrorIncidentFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ringLogBuffer: RingLogBuffer,
    private val dispatcherProvider: DispatcherProvider,
    private val rootSettings: RootSettings,
    private val adbSettings: AdbSettings,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
) {

    private val spoolDir = File(File(context.cacheDir, ErrorReportPackager.REPORTS_DIR), SPOOL_DIR)

    /**
     * @param occurredAt when the error actually happened, if the site knows; otherwise now, marked
     *        as approximate on the incident.
     */
    suspend fun freeze(
        error: Throwable,
        siteContext: Map<String, String?> = emptyMap(),
        occurredAt: Instant? = null,
    ): ErrorIncident {
        // Both taken before the settings reads below: those suspend, and the ring buffer keeps
        // evicting while they do, which would cost the log trail leading up to the failure.
        val frozenAt = occurredAt ?: Clock.System.now()
        val logSnapshot = ringLogBuffer.snapshot()

        val incidentId = Uuid.random().toString().take(8)
        log(TAG) { "freeze($incidentId): ${error.javaClass.name}" }

        val merged = buildMap {
            siteContext.forEach { (key, value) -> if (value != null) put(key, value) }
            put("access.root.consent", safeRead { rootSettings.useRoot.value() }.orUnknown())
            put("access.root.lastKnown", safeRead { rootManager.lastKnownRooted }.orUnknown())
            put("access.adb.consent", safeRead { adbSettings.useShizuku.value() }.orUnknown())
            put("access.adb.lastKnown", safeRead { shizukuManager.lastShizukudResult }.orUnknown())
        }

        return ErrorIncident(
            incidentId = incidentId,
            occurredAt = frozenAt,
            occurredAtIsApproximate = occurredAt == null,
            error = error,
            context = merged,
            logFile = spoolLog(incidentId, logSnapshot),
        )
    }

    /**
     * Drops the spool files left behind by a previous process. Reached only through
     * [ErrorIncidentStore], which calls it before it holds any incident of its own.
     */
    suspend fun clearStaleSpools() = withContext(dispatcherProvider.IO) {
        val stale = spoolDir.listFiles() ?: return@withContext
        if (stale.isEmpty()) return@withContext
        log(TAG, INFO) { "clearStaleSpools(): dropping ${stale.size} spooled log trails" }
        stale.forEach { runCatching { it.delete() } }
    }

    private suspend fun spoolLog(incidentId: String, logSnapshot: String): File? = withContext(dispatcherProvider.IO) {
        try {
            spoolDir.mkdirs()
            val target = File(spoolDir, "$incidentId.log")
            target.writeText(logSnapshot)
            target
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log(TAG, WARN) { "Failed to spool log for $incidentId: ${t.asLog()}" }
            null
        }
    }

    /**
     * A report must be producible from a broken install too: any single field may be unreadable
     * (a corrupt DataStore file) without taking the freeze with it. Explorer freezes inside a flow
     * collector whose handler turns an escaped throwable into a fatal workspace error.
     */
    private inline fun <T> safeRead(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log(TAG, WARN) { "Capability read failed: ${t.asLog()}" }
        null
    }

    private fun Any?.orUnknown(): String = this?.toString() ?: "unknown"

    companion object {
        private val TAG = logTag("Error", "Incident", "Factory")
        private const val SPOOL_DIR = "incidents"
    }
}
