package eu.darken.butler.common.files.archive

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.formatFileSize

/** The archive is valid, but uses something this app cannot read, see [reason]. */
class ArchiveUnsupportedException(
    val container: APath<*>,
    val reason: Reason,
    cause: Throwable? = null,
) : ReadException("Unsupported archive: $reason", container, cause) {

    sealed interface Reason {
        /** RAR archives older than RAR5. */
        data object Rar4 : Reason

        /** Unpacking needs a dictionary of [requiredBytes], more than the [availableBytes] memory set aside for it. */
        data class DictionaryTooLarge(val requiredBytes: Long, val availableBytes: Long) : Reason

        /** The container is part [partNumber] (counted from 1) of a multi-part archive, not its first part. */
        data class NotFirstPart(val partNumber: Int) : Reason

        /** A RAR compression method, filter, encryption or size beyond what this app supports. */
        data object Feature : Reason
    }

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "ArchiveUnsupportedException".toCaString(),
        description = caString { cx ->
            val archive = container.userReadablePath.get(cx)
            when (reason) {
                Reason.Rar4 -> cx.getString(R.string.general_error_archive_rar4_msg, archive)
                is Reason.DictionaryTooLarge -> cx.getString(
                    R.string.general_error_archive_dictionary_too_large_msg,
                    archive,
                    formatFileSize(cx, reason.requiredBytes),
                    formatFileSize(cx, reason.availableBytes),
                )
                is Reason.NotFirstPart -> cx.getString(R.string.general_error_archive_not_first_part_msg, archive, reason.partNumber)
                Reason.Feature -> cx.getString(R.string.general_error_archive_unsupported_feature_msg, archive)
            }
        },
    )
}
