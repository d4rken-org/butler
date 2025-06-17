package eu.darken.butler.workspace.core

import android.os.Parcelable
import java.util.UUID

interface Workspace {
    enum class Type {
        TEMPLATES,
        EXPLORER,
        SEARCHER,
        EDITOR,
        ;
    }

    data class Id(
        val id: UUID = UUID.randomUUID(),
    )

    interface Arguments : Parcelable {
        val type: Type
    }
}
