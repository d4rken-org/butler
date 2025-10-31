package eu.darken.butler.explorer.ui.explorer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Custom ActivityResultContract for opening document trees with pre-configured Intent.
 *
 * Unlike [ActivityResultContracts.OpenDocumentTree] which accepts a Uri? input,
 * this contract accepts a full Intent, preserving all extras including EXTRA_INITIAL_URI.
 * This enables pre-navigating the SAF picker to specific directories like Android/data.
 */
class OpenDocumentTreeWithIntent : ActivityResultContract<Intent, Uri?>() {

    override fun createIntent(context: Context, input: Intent): Intent = input

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK && intent != null) {
            intent.data
        } else {
            null
        }
    }
}
