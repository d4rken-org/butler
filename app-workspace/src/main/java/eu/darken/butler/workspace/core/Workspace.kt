package eu.darken.butler.workspace.core

import android.os.Parcelable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.parcel.UuidParceler
import eu.darken.butler.workspace.core.preview.PreviewData
import kotlinx.coroutines.flow.Flow
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlin.uuid.Uuid

interface Workspace {
    val id: Id
    val type: Type
    val info: Flow<Info>

    suspend fun release() {

    }

    enum class Type {
        TEMPLATES,
        EXPLORER,
        SEARCHER,
        EDITOR,
        ;
    }

    @Parcelize
    @TypeParceler<Uuid, UuidParceler>
    data class Id(
        val id: Uuid = Uuid.random(),
    ) : Parcelable {
        val shortTag: String
            get() = id.toString().take(4)
        val longTag: String
            get() = id.toString()

        override fun toString(): String = "Workspace.Id($shortTag)"
    }

    interface Arguments : Parcelable {
        val type: Type
    }

    data class Info(
        val id: Id,
        val type: Type,
        val title: CaString,
        val subtitle: CaString? = null,
        val operationCount: Int = 0,
        val attentionCount: Int = 0,
        val previewData: PreviewData? = null,
    )
}
