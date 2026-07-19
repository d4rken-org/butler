package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector

// Walks with a single fixed set of ops, so a symlink whose target needs ROOT/ADB to resolve or stat
// is simply not followed. Escalation-aware walking is handled by RoutedLocalWalker/IndirectLocalWalker.
class DirectLocalWalker(
    private val fileSystemOps: FileSystemOps<LocalPath, LocalPathLookup>,
    private val start: LocalPath,
    private val lookupOptions: LookupOptions,
    private val onFilter: suspend (LocalPathLookup) -> Boolean = { true },
    private val onError: suspend (LocalPathLookup, Exception) -> Boolean = { _, _ -> true },
    private val followSymlinks: Boolean = false,
) : AbstractFlow<LocalPathLookup>() {
    private val tag = "$TAG#${hashCode()}"

    override suspend fun collectSafely(collector: FlowCollector<LocalPathLookup>) {
        LocalWalkerCore(
            strategy = Strategy(),
            start = start,
            onFilter = onFilter,
            onError = onError,
            followSymlinks = followSymlinks,
            tag = tag,
        ).walk(collector)
    }

    private inner class Strategy : WalkerStrategy {
        override suspend fun lookupStart(start: LocalPath): LocalPathLookup =
            fileSystemOps.lookup(start, lookupOptions)

        override suspend fun list(dir: LocalPathLookup): WalkerStrategy.Listing =
            WalkerStrategy.Listing.Children(fileSystemOps.lookupFiles(dir.lookedUp, lookupOptions))

        override suspend fun canonicalize(path: LocalPath): LocalPath =
            fileSystemOps.canonicalize(path)

        override suspend fun lookup(path: LocalPath): LocalPathLookup =
            fileSystemOps.lookup(path, lookupOptions)
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "Walker", "Direct")
    }
}
