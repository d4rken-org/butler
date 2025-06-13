package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.CaString
import java.util.UUID

interface Workspace {
    enum class Type {
        NEW,
        EXPLORER,
        SEARCH,
        ;
    }

    data class Id(
        val id: UUID = UUID.randomUUID(),
    )

    interface Tab {
        val id: Id
        val title: CaString
        val type: Type
    }

    data class WorkspaceTab(
        override val id: Id = Id(),
        override val title: CaString,
        override val type: Type = Type.NEW
    ) : Tab
}
