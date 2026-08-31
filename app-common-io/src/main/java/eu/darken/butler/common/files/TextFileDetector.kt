package eu.darken.butler.common.files

/**
 * Call-shape convenience over [MimeInfo], which owns the table. Every overload answers from it, so
 * a name, a type and a path never disagree about the same file.
 */
object TextFileDetector {

    /**
     * Checks if a file is a text file based on its MIME type
     */
    fun isTextFile(mimeInfo: MimeInfo): Boolean {
        return mimeInfo.isText
    }

    /**
     * Checks if a file is a text file based on its file name
     */
    fun isTextFile(fileName: String): Boolean {
        return MimeInfo.fromFileName(fileName).isText
    }

    /**
     * Checks if a file is a text file based on its path
     */
    fun isTextFile(path: APath<*>): Boolean {
        return isTextFile(path.name)
    }
}
