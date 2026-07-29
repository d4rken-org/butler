package eu.darken.butler.apps.core

import android.content.Context
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.setup.core.SetupStateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

/**
 * Shared, in-memory store of app storage sizes. Sizes are expensive to query, so they are only
 * measured on demand and dropped whenever the underlying package data changes.
 */
@Singleton
class AppSizeCache @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val pkgRepo: PkgRepo,
    private val pkgOps: PkgOps,
    setupStateProvider: SetupStateProvider,
) {

    data class AppSize(
        val appBytes: Long,
        val dataBytes: Long,
        val cacheBytes: Long,
    ) {
        val total: Long
            get() = appBytes + dataBytes + cacheBytes

        companion object {
            // Disjoint components: StorageStats.dataBytes contains cacheBytes, but the details
            // breakdown labels them as separate buckets and sums them back up.
            fun from(stats: PkgOps.SizeStats) = AppSize(
                appBytes = stats.appBytes,
                dataBytes = (stats.dataBytes - stats.cacheBytes).coerceAtLeast(0L),
                cacheBytes = stats.cacheBytes,
            )
        }
    }

    /**
     * One atomically swapped object so revision and contents can never disagree.
     *
     * [attempted] is tracked separately from [sizes]: a query that failed still counts as measured,
     * otherwise every failing id would be retried on every later trigger.
     */
    data class Snapshot(
        val revision: Long = 0L,
        val sizes: Map<InstallId, AppSize> = emptyMap(),
        val attempted: Set<InstallId> = emptySet(),
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving

    private val _isAvailable = MutableStateFlow(Permission.PACKAGE_USAGE_STATS.isGranted(context))
    val isAvailable: StateFlow<Boolean> = _isAvailable

    // Guards every read-modify-write of _snapshot, including invalidation. Never held while querying.
    private val stateMutex = Mutex()

    // Serializes resolve batches so two callers don't measure the same ids twice.
    private val batchMutex = Mutex()

    init {
        var lastPkgRevision = pkgRepo.revision.value

        pkgRepo.revision
            .onEach { revision ->
                refreshAvailability()
                if (revision == lastPkgRevision) return@onEach
                lastPkgRevision = revision
                log(TAG, INFO) { "Package data changed (revision=$revision), dropping cached sizes" }
                invalidateAll()
            }
            .launchIn(appScope)

        // Only a trigger - the direct permission check below is the truth, so access granted
        // outside of Butler recovers too.
        setupStateProvider.state
            .onEach { refreshAvailability() }
            .launchIn(appScope)
    }

    fun refreshAvailability() {
        val granted = Permission.PACKAGE_USAGE_STATS.isGranted(context)
        if (_isAvailable.value != granted) {
            log(TAG, INFO) { "Usage access availability changed: $granted" }
        }
        _isAvailable.value = granted
    }

    suspend fun resolve(pkgs: Collection<Installed>) {
        refreshAvailability()
        if (!_isAvailable.value) {
            log(TAG) { "resolve(${pkgs.size}): usage access unavailable" }
            return
        }

        batchMutex.withLock {
            val startRevision: Long
            val todo: List<Installed>
            stateMutex.withLock {
                val current = _snapshot.value
                startRevision = current.revision
                todo = pkgs.filter { it.installId !in current.attempted }
            }
            if (todo.isEmpty()) {
                log(TAG, VERBOSE) { "resolve(${pkgs.size}): nothing left to measure" }
                return@withLock
            }

            log(TAG) { "resolve(${pkgs.size}): measuring ${todo.size} pkgs" }
            _isResolving.value = true
            try {
                val measured = withContext(dispatcherProvider.IO) {
                    val semaphore = Semaphore(MAX_PARALLEL_QUERIES)
                    todo
                        .map { pkg ->
                            async {
                                semaphore.withPermit {
                                    currentCoroutineContext().ensureActive()
                                    pkg.installId to querySize(pkg)
                                }
                            }
                        }
                        .awaitAll()
                }
                val sizes = measured.mapNotNull { (installId, size) -> size?.let { installId to it } }

                stateMutex.withLock {
                    val current = _snapshot.value
                    if (current.revision != startRevision) {
                        log(TAG, INFO) {
                            "resolve(): revision moved $startRevision->${current.revision}, discarding batch"
                        }
                        return@withLock
                    }
                    _snapshot.value = current.copy(
                        sizes = current.sizes + sizes,
                        attempted = current.attempted + todo.map { it.installId },
                    )
                }
                log(TAG) { "resolve(): ${sizes.size}/${todo.size} pkgs measured" }
            } finally {
                _isResolving.value = false
            }
        }
    }

    /** Drops the given ids so they are measured again, and discards any batch in flight. */
    suspend fun invalidate(installIds: Collection<InstallId>) {
        if (installIds.isEmpty()) return
        stateMutex.withLock {
            val current = _snapshot.value
            log(TAG) { "invalidate(${installIds.size} ids)" }
            _snapshot.value = Snapshot(
                revision = current.revision + 1,
                sizes = current.sizes - installIds.toSet(),
                attempted = current.attempted - installIds.toSet(),
            )
        }
    }

    suspend fun invalidateAll() {
        stateMutex.withLock {
            log(TAG) { "invalidateAll()" }
            _snapshot.value = Snapshot(revision = _snapshot.value.revision + 1)
        }
    }

    // The default UUID returns nothing (or wrong figures) for apps on adopted/private external storage.
    private suspend fun querySize(pkg: Installed): AppSize? {
        val storageUuid: Uuid = (pkg.applicationInfo?.storageUuid ?: StorageManager.UUID_DEFAULT).toKotlinUuid()
        return pkgOps.querySizeStats(pkg.installId, storageUuid)?.let { AppSize.from(it) }
    }

    companion object {
        private val TAG = logTag("Apps", "SizeCache")
        private const val MAX_PARALLEL_QUERIES = 4
    }
}
