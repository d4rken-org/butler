package eu.darken.butler.workspace.core

import android.os.Parcelable
import java.util.UUID

interface Workspace {
    val id: Id
    val type: Type

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
}
