package eu.darken.butler.editor.core.engine

import android.text.format.Formatter
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.editor.R

/**
 * A copy/cut was refused because the selection exceeds a clipboard's capacity - BEFORE the
 * selection was materialized where possible. [limitBytes] is the capacity shown to the user;
 * char-based caps report their UTF-16 byte equivalent so the message stays in file-size units.
 */
class ClipboardCapacityException(
    val limitBytes: Long,
    cause: Throwable? = null,
) : IllegalStateException("Selection exceeds clipboard capacity ($limitBytes bytes)", cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_error_clipboard_too_large_label.toCaString(),
        description = caString {
            it.getString(
                R.string.editor_error_clipboard_too_large,
                Formatter.formatShortFileSize(it, limitBytes),
            )
        },
    )
}
