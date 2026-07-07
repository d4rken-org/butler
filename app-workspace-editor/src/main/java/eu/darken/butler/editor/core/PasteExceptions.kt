package eu.darken.butler.editor.core

import android.text.format.Formatter
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.editor.R

/** A file exceeded [PasteFileReader.MAX_PASTE_FILE_SIZE] and was refused for pasting. */
class PasteTooLargeException(
    val maxBytes: Long,
) : IllegalArgumentException("File too large to paste (max $maxBytes bytes)"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_error_paste_too_large_label.toCaString(),
        description = caString {
            it.getString(
                R.string.editor_error_paste_too_large,
                Formatter.formatShortFileSize(it, maxBytes),
            )
        },
    )
}

/** A file's content looked binary (null bytes) and was refused for pasting. */
class PasteBinaryException : IllegalArgumentException("Cannot paste binary file content"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_error_paste_binary_label.toCaString(),
        description = R.string.editor_error_paste_binary.toCaString(),
    )
}
