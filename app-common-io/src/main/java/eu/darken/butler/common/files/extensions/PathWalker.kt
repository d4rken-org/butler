package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector
import java.io.IOException
import java.util.LinkedList

// TODO support symlinks?
// TODO unit test coverage
class PathWalker<
        P : APath,
        PL : APathLookup<P>,
        PLE : APathLookupExtended<P>,
        GT : APathGateway<P, PL, PLE>
        >(
    private val gateway: GT,
    private val start: P,
    private val onFilter: suspend (PL) -> Boolean = { true },
    private val onError: suspend (PL, Exception) -> Boolean = { _, _ -> true }
) : AbstractFlow<PL>() {
    private val tag = "$TAG#${hashCode()}"
    override suspend fun collectSafely(collector: FlowCollector<PL>) {
        val startLookUp = start.lookup(gateway)
        if (startLookUp.isFile) {
            collector.emit(startLookUp)
            return
        }

        val queue = LinkedList(listOf(startLookUp))

        while (!queue.isEmpty()) {

            val lookUp = queue.removeFirst()

            val newBatch = try {
                lookUp.lookedUp.lookupFiles(gateway)
            } catch (e: IOException) {
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
        private val TAG = logTag("Gateway", "Walker")
    }
}