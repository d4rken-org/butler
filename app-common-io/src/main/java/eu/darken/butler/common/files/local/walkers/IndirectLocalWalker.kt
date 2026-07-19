package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector

// Walks through the gateway with a single fixed mode for the entire traversal. Symlink resolution
// is mode-aware (canonicalize/lookup route through ROOT/ADB when needed).
class IndirectLocalWalker(
    private val gateway: LocalGateway,
    private val mode: LocalGateway.Mode = LocalGateway.Mode.AUTO,
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
            gateway.lookup(start, lookupOptions, mode)

        override suspend fun list(dir: LocalPathLookup): WalkerStrategy.Listing =
            WalkerStrategy.Listing.Children(gateway.lookupFiles(dir.lookedUp, lookupOptions, mode))

        override suspend fun canonicalize(path: LocalPath): LocalPath =
            gateway.canonicalize(path, mode)

        override suspend fun lookup(path: LocalPath): LocalPathLookup =
            gateway.lookup(path, lookupOptions, mode)
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "Walker", "Indirect")
    }
}
