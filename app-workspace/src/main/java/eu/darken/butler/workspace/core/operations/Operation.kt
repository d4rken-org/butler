package eu.darken.butler.workspace.core.operations

import android.os.Parcelable
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
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
        val operationId: Uuid = Uuid.random(),
    ) : Parcelable {
        val shortTag: String
            get() = operationId.toString().take(4)
        val longTag: String
            get() = operationId.toString()
    }

    interface Metadata {
        val origin: Origin
        val icon: ImageVector
        val title: CaString
        val description: CaString?

        sealed interface Origin {
            val workspaceId: Workspace.Id

            data class Explorer(override val workspaceId: Workspace.Id) : Origin
        }
    }

    val metadata: Metadata

    interface State {
        val startedAt: Instant

        data class Queued(
            override val startedAt: Instant,
        ) : State

        interface Active : State {
            val progress: Progress.Data
        }

        interface Waiting : State {
            val waitingSince: Instant
            val reason: CaString
        }

        interface Completed : State {
            val completedAt: Instant
            val summary: CaString
            val error: Throwable?
        }
    }

    data class Context(
        val id: Id,
        val startedAt: Instant,
    )

    suspend fun execute(operationContext: Context): Flow<State>
}