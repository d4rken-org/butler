package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.editor.R
import java.io.IOException
import java.nio.charset.Charset

/**
 * The document still holds bytes [charset] can't decode. Rewriting it would replace them with
 * U+FFFD for good, so whole-file rewrites refuse until the file is opened with the right encoding.
 */
class UndecodableBytesException(
    val charset: Charset,
) : IOException("Document holds bytes that aren't valid ${charset.name()}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_encoding_dialog_title.toCaString(),
        description = R.string.editor_error_undecodable_bytes.toCaString(charset.name()),
    )
}
