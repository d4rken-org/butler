package eu.darken.butler.explorer.core.sizes

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.storage.ExternalStorageStatsProvider

data class AndroidDataEstimate(
    val target: ExternalStorageStatsProvider.Target,
    val stats: ExternalStorageStatsProvider.Snapshot,
    val outsideAllocatedBytes: Long,
    val measuredDataAllocatedBytes: Long,
    val missingAllocatedBytes: Long,
    val displayedBytes: Long,
) {
    val path: LocalPath get() = target.root.child("Android", "data")

    enum class Failure { INCOMPLETE_COVERAGE, STATISTICS_UNAVAILABLE, INCONSISTENT_TOTAL }
}
