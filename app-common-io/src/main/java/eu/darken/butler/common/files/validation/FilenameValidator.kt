package eu.darken.butler.common.files.validation

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.smb.SmbLocationInput
import javax.inject.Inject

class FilenameValidator @Inject constructor() {

    enum class StorageContext {
        PUBLIC,  // Android scoped storage restrictions
        ROOT,    // Minimal Linux filesystem restrictions
        SAF,     // Storage Access Framework restrictions
        SMB      // Windows/SMB server restrictions
    }

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(
            val invalidChars: Set<Char>,
            val context: StorageContext
        ) : ValidationResult()

        /**
         * The name is unusable as a whole rather than because of a character it contains, e.g. `..`
         * or a trailing dot. Reported separately because there is no character to point at.
         */
        data class InvalidName(
            val reason: SmbLocationInput.NameIssue,
            val context: StorageContext
        ) : ValidationResult()
    }

    fun validate(name: String, parentPath: APath<*>): ValidationResult {
        if (name.isBlank()) return ValidationResult.Valid

        val context = detectStorageContext(parentPath)
        val restrictedChars = when (context) {
            StorageContext.PUBLIC -> ANDROID_SCOPED_STORAGE_CHARS
            StorageContext.ROOT -> LINUX_FILESYSTEM_CHARS
            StorageContext.SAF -> SAF_RESTRICTED_CHARS
            StorageContext.SMB -> SmbLocationInput.RESTRICTED_NAME_CHARS
        }

        val foundInvalidChars = name.filter { it in restrictedChars }.toSet()
        if (foundInvalidChars.isNotEmpty()) return ValidationResult.Invalid(foundInvalidChars, context)

        if (context == StorageContext.SMB) {
            SmbLocationInput.nameIssue(name)?.let { return ValidationResult.InvalidName(it, context) }
        }

        return ValidationResult.Valid
    }

    private fun detectStorageContext(path: APath<*>): StorageContext {
        return when (path) {
            is LocalPath -> when {
                path.path.startsWith("/storage/emulated/") -> StorageContext.PUBLIC
                path.path.startsWith("/sdcard/") -> StorageContext.PUBLIC
                else -> StorageContext.ROOT
            }
            is SAFPath -> StorageContext.SAF
            is SmbPath -> StorageContext.SMB
            else -> StorageContext.ROOT
        }
    }

    companion object {
        // Android scoped storage (FUSE) restrictions
        private val ANDROID_SCOPED_STORAGE_CHARS = setOf('<', '>', ':', '"', '|', '?', '*')

        // Linux filesystem restrictions (ext4, f2fs, etc)
        private val LINUX_FILESYSTEM_CHARS = setOf('/', '\u0000')

        // Storage Access Framework restrictions
        private val SAF_RESTRICTED_CHARS = setOf('/', '\u0000')
    }
}
