package eu.darken.butler.workspace.core.operations

import android.os.Parcelable
import eu.darken.butler.common.parcel.UuidParceler
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlin.uuid.Uuid

interface Operation {

    @Parcelize
    @TypeParceler<Uuid, UuidParceler>
    data class Id(
        val workspaceId: Workspace.Id,
        val operationId: Uuid = Uuid.random(),
    ) : Parcelable

    val operationId: Id
}