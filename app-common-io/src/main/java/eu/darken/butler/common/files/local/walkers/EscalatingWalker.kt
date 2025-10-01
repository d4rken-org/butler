package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.core.local.listFiles2
import eu.darken.butler.common.files.extensions.toFile
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.isFile
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.performLookup
import eu.darken.butler.common.files.local.toLocalPath
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector
import java.util.LinkedList

/**
 * Prevents unnecessary lookups in Mode.NORMAL for nested directories
 */
// TODO support symlinks?
// TODO unit test coerage
class EscalatingWalker(
    private val gateway: LocalGateway,
    private val start: LocalPath,
    private val options: APathGateway.WalkOptions<LocalPath, LocalPathLookup> = APathGateway.WalkOptions()
) : AbstractFlow<LocalPathLookup>() {
    private val tag = "$TAG#${hashCode()}"

    override suspend fun collectSafely(collector: FlowCollector<LocalPathLookup>) {
        val startLookUp = gateway.lookup(start)

        if (startLookUp.isFile) {
            collector.emit(startLookUp)
            return
        }

        val escalationMode = when {
            gateway.hasRoot() -> LocalGateway.Mode.ROOT
            gateway.hasAdb() -> LocalGateway.Mode.ADB
            else -> null
        }

        val queue = LinkedList<QueuedItem>().apply {
            add(QueuedItem(startLookUp, LocalGateway.Mode.NORMAL))
        }

        while (!queue.isEmpty()) {
            val item = queue.removeFirst()

            when {
                item.targetMode == LocalGateway.Mode.NORMAL -> {
                    try {
                        item.target.lookedUp.toFile()
                            .listFiles2()
                            .map { it.toLocalPath().performLookup() }
                            .filter {
                                val allowed = options.onFilter?.invoke(it) ?: true
                                if (Bugs.isTrace && !allowed) {
                                    log(tag, Logging.Priority.VERBOSE) { "Skipping (filter): $it" }
                                }
                                allowed
                            }
                            .forEach { child ->
                                if (child.isDirectory) {
                                    if (Bugs.isTrace) log(tag, Logging.Priority.VERBOSE) { "Walking: $child" }
                                    queue.addFirst(item.toSubItem(child))
                                }
                                collector.emit(child)
                            }
                        continue
                    } catch (e: Exception) {
                        log(
                            TAG,
                            Logging.Priority.VERBOSE
                        ) { "Escalating ${item.target.lookedUp} to $escalationMode due to: $e" }
                        queue.addFirst(item.copy(targetMode = escalationMode, error = e))
                    }
                }

                item.targetMode == LocalGateway.Mode.ROOT || item.targetMode == LocalGateway.Mode.ADB -> {
                    try {
                        gateway
                            .walk(
                                path = item.target.lookedUp,
                                options = options,
                                mode = item.targetMode
                            )
                            .collect { child ->
                                // `walk` already processes all subdirectories, no need to queue them again
                                collector.emit(child)
                            }
                        continue
                    } catch (e: Exception) {
                        log(
                            TAG,
                            Logging.Priority.DEBUG
                        ) { "Failed to read despite escalation: ${item.target.lookedUp}: $e" }
                        queue.addFirst(item.copy(targetMode = null, error = e))
                    }
                }

                item.error != null -> {
                    log(TAG, Logging.Priority.WARN) { "Failed to read ${item.target}: ${item.error}" }
                    if (options.onError?.invoke(item.target, item.error) != false) {
                        continue
                    } else {
                        throw item.error
                    }
                }
            }
        }
    }

    data class QueuedItem(
        val target: LocalPathLookup,
        val targetMode: LocalGateway.Mode? = LocalGateway.Mode.NORMAL,
        val error: Exception? = null,
    ) {
        fun toSubItem(target: LocalPathLookup) = copy(
            target = target,
            error = null,
        )
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "Walker", "Escalating")
    }
}