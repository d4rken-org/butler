package eu.darken.butler.common.error

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.compression.Zipper
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The zip a share intent points at, plus the payload that went into it. */
data class PackagedErrorReport(
    val uri: Uri,
    val payload: ErrorReportPayload,
)

/**
 * Turns a frozen [ErrorIncident] into a shareable zip holding [STACKTRACE_FILE], [REPORT_FILE] and
 * [LOG_FILE].
 *
 * Failures propagate: a caller that asked for a report gets the reason it has none rather than a
 * silently missing share.
 */
@Singleton
class ErrorReportPackager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val butlerId: ButlerId,
    private val json: Json,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val reportsDir = File(context.cacheDir, REPORTS_DIR)
    private val lock = Mutex()

    suspend fun packageReport(incident: ErrorIncident, summary: String?): PackagedErrorReport =
        withContext(dispatcherProvider.IO) {
            lock.withLock {
                log(TAG) { "packageReport(${incident.incidentId}, summary=$summary)" }
                val stackTrace = incident.error.asLog()
                val payload = buildPayload(incident, summary, stackTrace)

                val nonce = Uuid.random().toString().take(8)
                val stagingDir = File(reportsDir, "$TMP_PREFIX${incident.incidentId}-$nonce")
                val stagingZip = File(reportsDir, "$TMP_PREFIX${incident.incidentId}-$nonce.zip")
                val finalZip = File(reportsDir, "${incident.incidentId}.zip")

                try {
                    reportsDir.mkdirs()
                    stagingDir.deleteRecursively()
                    stagingDir.mkdirs()

                    File(stagingDir, STACKTRACE_FILE).writeText(stackTrace)
                    File(stagingDir, REPORT_FILE)
                        .writeText(json.encodeToString(ErrorReportPayload.serializer(), payload))
                    val logFile = File(stagingDir, LOG_FILE)
                    val spooled = incident.logFile?.takeIf { it.exists() }
                    if (spooled != null) spooled.copyTo(logFile, overwrite = true)
                    else logFile.writeText(NO_LOG_PLACEHOLDER)

                    // Zipper writes straight to its output path, so a mid-write failure on the final
                    // name would leave a corrupt zip that later shares hand out.
                    Zipper().zip(
                        listOf(
                            File(stagingDir, STACKTRACE_FILE).path,
                            File(stagingDir, REPORT_FILE).path,
                            logFile.path,
                        ),
                        stagingZip.path,
                    )
                    finalZip.delete()
                    if (!stagingZip.renameTo(finalZip)) {
                        stagingZip.copyTo(finalZip, overwrite = true)
                        stagingZip.delete()
                    }
                } catch (t: Throwable) {
                    runCatching { stagingZip.delete() }
                    throw t
                } finally {
                    runCatching { stagingDir.deleteRecursively() }
                }

                prune()

                PackagedErrorReport(
                    uri = FileProvider.getUriForFile(
                        context,
                        "${BuildConfigWrap.APPLICATION_ID}.provider",
                        finalZip,
                    ),
                    payload = payload,
                )
            }
        }

    private fun buildPayload(
        incident: ErrorIncident,
        summary: String?,
        stackTrace: String,
    ): ErrorReportPayload = ErrorReportPayload(
        incidentId = incident.incidentId,
        installId = safeField { butlerId.id },
        occurredAt = incident.occurredAt,
        occurredAtIsApproximate = incident.occurredAtIsApproximate,
        packagedAt = Clock.System.now(),
        summary = summary,
        app = ErrorReportPayload.App(
            version = safeField { BuildConfigWrap.VERSION_DESCRIPTION },
            flavor = safeField { BuildConfigWrap.FLAVOR.name },
            buildType = safeField { BuildConfigWrap.BUILD_TYPE.name },
            mapId = extractMapId(stackTrace),
        ),
        device = ErrorReportPayload.Device(
            fingerprint = safeField { Build.FINGERPRINT },
            apiLevel = safeField { Build.VERSION.SDK_INT.toString() },
            locale = safeField { Resources.getSystem().configuration.locales.toLanguageTags() },
        ),
        error = ErrorReportPayload.Error(
            className = safeField { incident.error.javaClass.name },
            message = incident.error.message,
            causeChain = incident.error.renderCauseChain(),
        ),
        context = incident.context,
    )

    /**
     * Keep the newest [MAX_KEPT] zips. Staging entries are only reaped past [TMP_GRACE_MS], never
     * within the window a concurrent package may still own them.
     */
    private fun prune() {
        val now = System.currentTimeMillis()
        val children = reportsDir.listFiles() ?: return
        children
            .filter { it.name.startsWith(TMP_PREFIX) && now - it.lastModified() > TMP_GRACE_MS }
            .forEach { runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() } }
        children
            .filter { it.isFile && !it.name.startsWith(TMP_PREFIX) && it.name.endsWith(".zip") }
            .sortedByDescending { it.lastModified() }
            .drop(MAX_KEPT)
            .forEach {
                log(TAG) { "Pruning old report: ${it.name}" }
                runCatching { it.delete() }
            }
    }

    private inline fun safeField(block: () -> String): String = try {
        block()
    } catch (t: Throwable) {
        log(TAG, WARN) { "Field unavailable: ${t.asLog()}" }
        "unavailable: ${t.message}"
    }

    companion object {
        private val TAG = logTag("Error", "Report", "Packager")
        const val STACKTRACE_FILE = "stacktrace.txt"
        const val REPORT_FILE = "report.json"
        const val LOG_FILE = "report.log"
        const val REPORTS_DIR = "error_reports"
        private const val TMP_PREFIX = ".tmp-"
        private const val TMP_GRACE_MS = 60_000L
        private const val MAX_KEPT = 5
        private const val NO_LOG_PLACEHOLDER = "No log trail was captured for this incident."
    }
}
