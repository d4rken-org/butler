package eu.darken.butler.workspace.ui.manager

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class WorkspaceManagerColumnItemKey : Parcelable {
    @Parcelize
    data object StatusCard : WorkspaceManagerColumnItemKey()

    sealed class Workspace(open val id: eu.darken.butler.workspace.core.Workspace.Id) :
        WorkspaceManagerColumnItemKey() {
        @Parcelize
        data class Standard(override val id: eu.darken.butler.workspace.core.Workspace.Id) : Workspace(id)

        @Parcelize
        data class Compact(override val id: eu.darken.butler.workspace.core.Workspace.Id) : Workspace(id)

        @Parcelize
        data class Detailed(override val id: eu.darken.butler.workspace.core.Workspace.Id) : Workspace(id)
    }

    sealed class Explanation : WorkspaceManagerColumnItemKey() {
        @Parcelize
        data object BadgeExplanation : Explanation()

        @Parcelize
        data object ButtonBehaviorExplanation : Explanation()

        @Parcelize
        data object TutorialCard : Explanation()

        @Parcelize
        data object TipsCard : Explanation()
    }

    @Parcelize
    data object NewTab : WorkspaceManagerColumnItemKey()

    @Parcelize
    data class Custom(val type: String, val id: String) : WorkspaceManagerColumnItemKey()
}