package eu.darken.butler.provider.documents.query

import android.database.MatrixCursor
import android.os.Bundle
import android.provider.DocumentsContract

/**
 * MatrixCursor that supports error messages via DocumentsContract.EXTRA_ERROR.
 *
 * Android's file picker displays EXTRA_ERROR messages to users when present,
 * allowing DocumentsProvider to communicate permission requirements and other
 * access issues that prevent browsing.
 *
 * Usage:
 * ```
 * return ErrorMatrixCursor(
 *     columnNames = projection,
 *     errorMessage = "Root access required"
 * )
 * ```
 *
 * Phase 1: Permission errors
 * Phase 2: Additional error types (decode failures, etc.)
 */
class ErrorMatrixCursor(
    columnNames: Array<String>,
    private val errorMessage: String? = null,
) : MatrixCursor(columnNames) {

    override fun getExtras(): Bundle {
        return Bundle().apply {
            errorMessage?.let {
                putString(DocumentsContract.EXTRA_ERROR, it)
            }
        }
    }
}
