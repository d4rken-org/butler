package eu.darken.butler.workspace.core.operations

import android.os.Parcelable
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.parcel.UuidParceler
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface Operation {

    @Parcelize
    @TypeParceler<Uuid, UuidParceler>
    data class Id(
        val id: Uuid = Uuid.random(),
    ) : Parcelable {
        val shortTag: String
            get() = id.toString().take(4)
        val longTag: String
            get() = id.toString()

        override fun toString(): String = "Operation.Id($shortTag)"
    }

    interface Metadata {
        val origin: Origin
        val icon: ImageVector
        val title: CaString
        val description: CaString

        /**
         * File-op classification used by the global Operation History to filter and label entries.
         * `null` means this operation is not a file op and should NOT be persisted in history
         * (e.g., Developer test-data generators, Searcher search execution).
         */
        val kind: Kind? get() = null

        /**
         * Semantic intent override that refines the displayed label in history.
         * For example, a `MoveOperation` invoked as a rename should set `intent = RENAME` so the
         * history row reads "Renamed" instead of "Moved". Null = use the default label for `kind`.
         */
        val intent: Intent? get() = null

        /**
         * Paths the operation INTENDED to act on (sources for Copy/Move/Delete; parent for Create).
         * Captured at submit time so failed/cancelled ops are still queryable by path scope, even
         * when [Report.affectedPaths] is null/empty (because nothing was actually completed).
         * Persistence stores the union of intended + actually-affected paths.
         */
        val intendedPaths: Collection<APath<*>>? get() = null

        enum class Kind { COPY, MOVE, DELETE, RESTORE, CREATE_FOLDER, CREATE_FILE, SAVE, COMPRESS, EXTRACT }

        enum class Intent { RENAME, PASTE_COPY, PASTE_MOVE, DROP_COPY, DROP_MOVE }

        sealed interface Origin {
            val workspaceId: Workspace.Id

            data class Explorer(override val workspaceId: Workspace.Id) : Origin
            data class Searcher(override val workspaceId: Workspace.Id) : Origin
            data class Saver(override val workspaceId: Workspace.Id) : Origin
            data class Developer(override val workspaceId: Workspace.Id) : Origin
            data class Viewer(override val workspaceId: Workspace.Id) : Origin
        }
    }

    val metadata: Metadata

    interface State {
        val startedAt: Instant

        data class Queued(
            override val startedAt: Instant,
        ) : State

        interface Active : State {
            val primaryProgress: Progress.Data
            val secondaryProgress: Progress.Data?
        }

        interface Waiting : State {
            val waitingSince: Instant
            val reason: CaString
            val issue: Issue
        }

        interface Completed : State {
            val completedAt: Instant
            val summary: CaString
            val report: Report?
            val error: Throwable?
        }
    }

    interface Report {
        val summary: CaString
        val affectedPaths: Collection<PathChange>

        /**
         * Number of sub-items that DIDN'T complete as intended even though the operation as a whole
         * didn't fail (e.g., save with mixed permissions: some files succeed, some fail per-file
         * with the top-level [Operation.State.Completed.error] still null). Drives the
         * [eu.darken.butler.workspace.core.operations.history.HistoryOutcome.PARTIAL] outcome in history.
         * Default 0 means "not partial" — only reports that can produce per-item failures override.
         */
        val partialErrorCount: Int get() = 0

        data class PathChange(
            val path: APath<*>,
            val change: Change,
            /**
             * For [Change.MOVED]: the source path before the move (i.e., the rename source).
             * History details show as `previousPath → path`. Null for non-move changes or when
             * the source isn't tracked.
             */
            val previousPath: APath<*>? = null,
        ) {
            enum class Change {
                ADDED, REMOVED, MODIFIED, TRASHED, MOVED,
            }
        }
    }

    interface HasPerformanceHistory {
        val performanceHistory: PerformanceHistory?
    }

    data class Context(
        val id: Id,
        val startedAt: Instant,
    )

    fun perform(operationContext: Context): Flow<State>

    /**
     * Releases sensitive or transient data held by this operation (e.g. wiping a password buffer).
     * Invoked by [ManagedOperation] when the operation reaches a terminal state, and also when it is
     * cancelled before [perform] ever begins. Must be idempotent — it can be called more than once.
     * Default: nothing to release.
     */
    fun onDiscarded() {}
}