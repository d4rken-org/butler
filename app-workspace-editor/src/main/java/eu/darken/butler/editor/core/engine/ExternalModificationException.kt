package eu.darken.butler.editor.core.engine

import java.io.IOException

/** The file on disk no longer matches what the buffer loaded; saving would clobber foreign changes. */
class ExternalModificationException(message: String) : IOException(message)
