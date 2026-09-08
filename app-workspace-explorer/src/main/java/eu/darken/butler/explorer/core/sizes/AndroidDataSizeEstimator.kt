package eu.darken.butler.explorer.core.sizes

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.isAncestorOfOrSelf
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.storage.ExternalStorageStatsProvider

/** Coverage is accumulated for every event, independently of the capped list of scan problems. */
class AndroidDataSizeEstimator(private val root: LocalPath) {
    private val data = root.child("Android", "data")
    private val obb = root.child("Android", "obb")
    private var rootSeen = false
    private var outsideAllocated = 0L
    private var dataAllocated = 0L
    private var invalidAllocation = false
    private var outsideIncomplete = false
    private var obbIncomplete = false
    var dataIncomplete = false
        private set

    val canEstimate: Boolean get() = dataIncomplete && rootSeen && !outsideIncomplete && !invalidAllocation

    fun onEntry(lookup: APathLookup<*>) {
        val path = lookup.lookedUp
        if (path == root) {
            if (rootSeen) return
            rootSeen = true
        }
        if (!root.isAncestorOfOrSelf(path)) {
            outsideIncomplete = true
            return
        }
        if (lookup.error != null || lookup.fileType == FileType.UNKNOWN ||
            (lookup.fileType != FileType.DIRECTORY && lookup.size == null)
        ) onError(path)
        if (obb.isAncestorOfOrSelf(path)) return

        val allocated = (lookup as? LocalPathLookup)?.allocatedSize
        if (allocated == null || allocated < 0 || lookup.fileType == FileType.UNKNOWN ||
            (lookup.fileType != FileType.DIRECTORY && lookup.size == null)
        ) {
            invalidAllocation = true
            return
        }
        try {
            if (data.isAncestorOfOrSelf(path)) dataAllocated = Math.addExact(dataAllocated, allocated)
            else outsideAllocated = Math.addExact(outsideAllocated, allocated)
        } catch (_: ArithmeticException) {
            invalidAllocation = true
        }
    }

    fun onError(path: APath<*>) {
        when {
            data.isAncestorOfOrSelf(path) -> dataIncomplete = true
            obb.isAncestorOfOrSelf(path) -> obbIncomplete = true
            else -> outsideIncomplete = true
        }
    }

    fun apply(
        scan: DirectoryScan,
        target: ExternalStorageStatsProvider.Target,
        stats: ExternalStorageStatsProvider.Snapshot,
    ): DirectoryScan {
        if (!dataIncomplete) return scan
        if (!canEstimate) return scan.copy(estimateFailure = AndroidDataEstimate.Failure.INCOMPLETE_COVERAGE)
        if (scan.root != root || target.root != root || stats.totalBytes < 0) return inconsistent(scan)
        val missing = stats.totalBytes - outsideAllocated
        if (missing < dataAllocated) return inconsistent(scan)
        val contribution = missing - dataAllocated
        val affected = listOf(data, root.child("Android"), root)
        val replacements = try {
            affected.associate { path ->
                val measured = scan.sizes[path.path] ?: DirectorySize(0L, false)
                check(!measured.isEstimated)
                path.path to measured.copy(
                    bytes = Math.addExact(measured.bytes, contribution),
                    estimatedMissingBytes = contribution,
                    hasUnestimatedContent = path != data && obbIncomplete,
                )
            }
        } catch (_: ArithmeticException) {
            return inconsistent(scan)
        }
        return scan.copy(
            sizes = scan.sizes + replacements,
            estimate = AndroidDataEstimate(
                target = target,
                stats = stats,
                outsideAllocatedBytes = outsideAllocated,
                measuredDataAllocatedBytes = dataAllocated,
                missingAllocatedBytes = contribution,
                displayedBytes = replacements.getValue(data.path).bytes,
            ),
            estimateFailure = null,
        )
    }

    private fun inconsistent(scan: DirectoryScan) = scan.copy(
        estimateFailure = AndroidDataEstimate.Failure.INCONSISTENT_TOTAL,
    )
}
