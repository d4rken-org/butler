package eu.darken.butler.common.files.core.local

import android.system.Os
import android.system.OsConstants
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

fun File.tryMkDirs(): File {
    if (exists()) {
        if (isDirectory) {
            log(VERBOSE) { "Directory already exists, not creating: $this" }
            return this
        } else {
            throw IllegalStateException("Directory exists, but is not a directory: $this")
        }
    }

    if (mkdirs()) {
        log(VERBOSE) { "Directory created: $this" }
        return this
    } else {
        throw IllegalStateException("Couldn't create Directory: $this")
    }
}

fun File.tryMkFile(): File {
    if (exists()) {
        if (isFile) {
            log(VERBOSE) { "File already exists, not creating: $this" }
            return this
        } else {
            throw IllegalStateException("Path exists but is not a file: $this")
        }
    }

    if (parentFile?.exists() == false) parentFile?.tryMkDirs()

    if (createNewFile()) {
        log(VERBOSE) { "File created: $this" }
        return this
    } else {
        throw IllegalStateException("Couldn't create file: $this")
    }
}

@Throws(IOException::class)
fun File.deleteAll() {
    if (isDirectory) {
        listFiles()?.forEach { it.deleteAll() }
    }
    if (delete()) {
        log(VERBOSE) { "File.release(): Deleted $this" }
    } else if (!exists()) {
        log(WARN) { "File.release(): File didn't exist: $this" }
    } else {
        throw FileNotFoundException("Failed to delete file: $this")
    }
}

fun File.createSymlink(target: File): Boolean {
    return try {
        java.nio.file.Files.createSymbolicLink(this.toPath(), target.toPath())
        this.exists()
    } catch (e: Exception) {
        // Fallback to OS API if NIO fails
        try {
            Os.symlink(target.path, this.path)
            this.exists()
        } catch (e2: Exception) {
            false
        }
    }
}

fun File.readLink(): String? = try {
    java.nio.file.Files.readSymbolicLink(this.toPath()).toString()
} catch (e: Exception) {
    // Fallback to OS API if NIO fails
    try {
        Os.readlink(this.path)
    } catch (e2: Exception) {
        null
    }
}

val File.parents: Sequence<File>
    get() = sequence {
        var parent = parentFile
        while (parent != null) {
            yield(parent)
            parent = parent.parentFile
        }
    }

val File.parentsInclusive: Sequence<File>
    get() = sequenceOf(this) + parents

fun String.fixSlashes(): String = replace("/", File.separator)