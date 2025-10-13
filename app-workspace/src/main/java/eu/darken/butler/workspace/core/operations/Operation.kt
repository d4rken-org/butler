package eu.darken.butler.workspace.core.operations

import android.os.Parcelable
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath
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

        sealed interface Origin {
            val workspaceId: Workspace.Id

            data class Explorer(override val workspaceId: Workspace.Id) : Origin
            data class Searcher(override val workspaceId: Workspace.Id) : Origin
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

        data class PathChange(
            val path: APath,
            val change: Change,
        ) {
            enum class Change {
                ADDED, REMOVED, MODIFIED
            }
        }
    }

    data class Context(
        val id: Id,
        val startedAt: Instant,
    )

    fun perform(operationContext: Context): Flow<State>
}