package eu.darken.butler.workspace.ui.scroll

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceScrollPosition(
    val index: Int = 0,
    val offset: Int = 0,
) {
    val isTop: Boolean
        get() = index == 0 && offset == 0
}
