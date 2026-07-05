package eu.darken.butler.editor.core.engine

import java.io.IOException

/** The opened file is not writable; edits can be made but not saved back to it. */
class ReadOnlyFileException(message: String) : IOException(message)
