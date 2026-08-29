package eu.darken.butler.common.error

import android.content.ClipData
import android.content.Context
import android.content.Intent
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.R
import javax.inject.Inject

/**
 * Builds the share intent for a packaged error report. The zip carries the details; the mail body
 * only has to route it — no stack frames, which a mail client would rewrap into something unreadable
 * anyway.
 */
@Reusable
class ErrorReportTool @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun createShareChooserIntent(packaged: PackagedErrorReport): Intent {
        val payload = packaged.payload
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(
                    R.string.general_error_report_subject,
                    context.getString(R.string.app_name),
                    payload.incidentId,
                ),
            )
            putExtra(Intent.EXTRA_TEXT, buildRoutingBody(payload))
            putExtra(Intent.EXTRA_STREAM, packaged.uri)
            clipData = ClipData.newRawUri("", packaged.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(sendIntent, context.getString(R.string.general_share_error_action))
    }

    private fun buildRoutingBody(payload: ErrorReportPayload): String = buildString {
        appendLine(payload.app.version)
        appendLine(payload.device.fingerprint)
        appendLine("Incident: ${payload.incidentId}")
        payload.summary?.let { appendLine(it) }
        val firstMessageLine = payload.error.message?.lineSequence()?.firstOrNull()
        appendLine(listOfNotNull(payload.error.className, firstMessageLine).joinToString(": "))
        appendLine()
        append("Details are in the attached zip.")
    }
}
