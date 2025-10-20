package eu.darken.butler.common.files.local

import android.system.Os
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.core.local.isSymbolicLink
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import java.io.File

fun File.getAPathFileType(): FileType? = when {
    // Order matters!
    try {
        isSymbolicLink()
    } catch (e: Exception) {
        log(WARN) { "Failed to check 'isSymbolicLink' on $this: $e" }
        false
    } -> FileType.SYMBOLIC_LINK
    try {
        isDirectory
    } catch (e: Exception) {
        log(WARN) { "Failed to check 'isDirectory' on $this: $e" }
        false
    } -> FileType.DIRECTORY
    try {
        isFile
    } catch (e: Exception) {
        log(WARN) { "Failed to check 'isFile' on $this: $e" }
        false
    } -> FileType.FILE
    try {
        exists()
    } catch (e: Exception) {
        log(WARN) { "Failed to check 'exists' on $this: $e" }
        false
    } -> FileType.UNKNOWN
    else -> null
}

fun File.toLocalPath(): LocalPath = LocalPath.build(this)

fun File.setPermissions(permissions: Permissions): Boolean {
    Os.chmod(path, permissions.mode)
    return true
}

fun File.setOwnership(ownership: Ownership): Boolean {
    Os.lchown(path, ownership.userId.toInt(), ownership.groupId.toInt())
    return true
}