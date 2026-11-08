package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.editor.R
import java.io.IOException
import java.nio.charset.Charset

/** The document holds characters (or lone surrogates) that [charset] can't encode. */
class UnmappableCharactersException(
    val charset: Charset,
) : IOException("Document holds characters that can't be written as ${charset.name()}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_convert_encoding_dialog_title.toCaString(),
        description = R.string.editor_error_unmappable_characters.toCaString(charset.name()),
    )
}
