package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.editor.R
import java.io.IOException

/** The opened file is not writable; edits can be made but not saved back to it. */
class ReadOnlyFileException(
    message: String,
    private val description: CaString = R.string.editor_error_read_only.toCaString(),
) : IOException(message), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_infobar_read_only.toCaString(),
        description = description,
    )

    companion object {
        /** [ContentSource.File.isUndecodableReadOnly]: the reason the user sees is the encoding. */
        fun undecodable(action: String, source: ContentSource.File) = ReadOnlyFileException(
            "Invalid ${source.detectedCharset} bytes, $action is disabled: ${source.path}",
            R.string.editor_undecodable_banner_message.toCaString(),
        )
    }
}
