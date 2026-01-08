package eu.darken.butler.common.error

import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.R
import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.debug.logging.asLog
import javax.inject.Inject

@Reusable
class ErrorReportTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemClipboardHelper: SystemClipboardHelper,
) {
    fun buildReport(
        throwable: Throwable,
        message: String? = null,
        errorContext: String? = null,
        metadata: Map<String, String?> = emptyMap(),
    ): ErrorReport = ErrorReport(
        title = context.getString(R.string.general_error_report_title),
        deviceFingerprint = Build.FINGERPRINT,
        appVersion = BuildConfigWrap.VERSION_DESCRIPTION,
        customMessage = message,
        context = errorContext,
        errorMessage = throwable.message ?: throwable.javaClass.simpleName,
        stackTrace = throwable.asLog(),
        metadata = metadata,
    )

    fun copyToClipboard(report: ErrorReport) {
        systemClipboardHelper.copyToClipboard(report.toMarkdown())
    }

    fun createShareIntent(report: ErrorReport): Intent {
        val shareText = report.toMarkdown()
        val subject = context.getString(
            R.string.general_error_report_share_subject,
            context.getString(R.string.app_name),
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createShareChooserIntent(report: ErrorReport): Intent {
        val sendIntent = createShareIntent(report)
        return Intent.createChooser(sendIntent, context.getString(R.string.general_share_error_action))
    }
}
