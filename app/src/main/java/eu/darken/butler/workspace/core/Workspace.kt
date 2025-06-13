package eu.darken.butler.workspace.core

import java.util.UUID

interface Workspace {
    enum class Type {
        EXPLORER,
        SEARCH,
        EDITOR,
        ;
    }

    data class Id(
        val id: UUID = UUID.randomUUID(),
    )

    interface Tab {
        val id: Id
        val title: String
    }

    data class WorkspaceTab(
        override val id: Id = Id(),
        override val title: String
    ) : Tab
}
