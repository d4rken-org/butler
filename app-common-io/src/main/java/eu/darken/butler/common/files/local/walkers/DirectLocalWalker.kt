package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.isFile
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector
import java.util.LinkedList

// TODO support symlinks?
// TODO unit test coverage
class DirectLocalWalker(
    private val fileSystemOps: LocalFileSystemOps,
    private val start: LocalPath,
    private val lookupOptions: LookupOptions ,
    private val onFilter: suspend (LocalPathLookup) -> Boolean = { true },
    private val onError: suspend (LocalPathLookup, Exception) -> Boolean = { _, _ -> true },
) : AbstractFlow<LocalPathLookup>() {
    private val tag = "$TAG#${hashCode()}"

    override suspend fun collectSafely(collector: FlowCollector<LocalPathLookup>) {
        val startLookUp = fileSystemOps.lookup(start, lookupOptions)
        if (startLookUp.isFile) {
            collector.emit(startLookUp)
            return
        }

        val queue = LinkedList(mutableListOf(startLookUp))

        while (!queue.isEmpty()) {
            val lookUp = queue.removeFirst()

            val newBatch = try {
                // Use lookupFiles for efficient single-call operation
                fileSystemOps.lookupFiles(lookUp.lookedUp, lookupOptions)
            } catch (e: Exception) {
                log(TAG, Logging.Priority.ERROR) { "Failed to read $lookUp: $e" }
                if (onError(lookUp, e)) {
                    emptyList()
                } else {
                    throw e
                }
            }

            newBatch
                .filter {
                    val allowed = onFilter(it)
                    if (Bugs.isTrace) {
                        if (!allowed) log(tag, Logging.Priority.VERBOSE) { "Skipping (filter): $it" }
                    }
                    allowed
                }
                .forEach { child ->
                    if (child.isDirectory) {
                        if (Bugs.isTrace) log(tag, Logging.Priority.VERBOSE) { "Walking: $child" }
                        queue.addFirst(child)
                    }
                    collector.emit(child)
                }
        }
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "Walker", "Direct")
    }
}