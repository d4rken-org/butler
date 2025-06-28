package eu.darken.butler.workspace.core

import android.os.Parcelable
import eu.darken.butler.common.ca.CaString
import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface Workspace {
    val id: Id
    val type: Type
    val info: Flow<Info>

    enum class Type {
        TEMPLATES,
        EXPLORER,
        SEARCHER,
        EDITOR,
        ;
    }

    data class Id(
        val id: UUID = UUID.randomUUID(),
    ) {
        val shortTag: String
            get() = id.toString().take(4)
        val longTag: String
            get() = id.toString()
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
    )
}
