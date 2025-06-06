package eu.darken.butler.common.debug.recorder.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.darken.butler.R
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.WebpageTool

//class RecorderConsentDialog(
//    private val context: Context,
//    private val webpageTool: WebpageTool
//) {
//    fun showDialog(onStartRecord: () -> Unit) {
//        MaterialAlertDialogBuilder(context).apply {
//            setTitle(R.string.support_debuglog_label)
//            setMessage(R.string.settings_debuglog_explanation)
//            setPositiveButton(R.string.debug_debuglog_record_action) { _, _ -> onStartRecord() }
//            setNegativeButton(eu.darken.butler.common.R.string.general_cancel_action) { _, _ -> }
//            setNeutralButton(R.string.settings_privacy_policy_label) { _, _ ->
//                webpageTool.open(ButlerLinks.PRIVACY_POLICY)
//            }
//        }.show()
//    }
//}