package eu.darken.butler.common.coil.fetchers

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import javax.inject.Inject


class AppPreviewGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
) {

    suspend fun generate(
        lookup: APathLookup<*>,
        maxBytes: Long = MAX_BYTES_TO_READ,
        width: Int = PREVIEW_WIDTH,
        height: Int = PREVIEW_HEIGHT
    ): Bitmap {
        log(TAG) { "Generating text preview for: ${lookup.path}" }

        val textContent = readTextContent(lookup, maxBytes)
        return renderTextToBitmap(textContent, width, height)
    }

    companion object {
        private val TAG = logTag("Coil", "Fetcher", "App", "Generator")
    }
}