package eu.darken.butler.viewer.ui.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.NavigateBefore
import androidx.compose.material.icons.automirrored.twotone.NavigateNext
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.FolderZip
import androidx.compose.material.icons.twotone.InstallMobile
import androidx.compose.material.icons.twotone.OpenInBrowser
import androidx.compose.material.icons.twotone.SaveAlt
import androidx.compose.material.icons.twotone.Share
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.viewer.R
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBarItem
import eu.darken.butler.common.R as CommonR

/**
 * Workspace-level actions for the viewer, shown in the bottom action bar.
 *
 * [Install], [BrowseArchive], [OpenWith] and [Share] stay visible on the narrowest pane; the rest are
 * SECONDARY and fall into the overflow menu as space runs out, [Delete] first because it is the one
 * a mis-tap costs most.
 */
sealed interface ViewerActionBarItem : WorkspaceActionBarItem {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceActionBarItem.Group get() = WorkspaceActionBarItem.Group.PRIMARY
    override val badge: Boolean get() = false

    /** Step to the previous file of the listing this viewer was opened from. */
    data class PreviousFile(
        override val isEnabled: Boolean,
    ) : ViewerActionBarItem {
        override val icon = Icons.AutoMirrored.TwoTone.NavigateBefore
        override val label = R.string.viewer_previous_file_action.toCaString()
    }

    /** Step to the next file of that same listing. */
    data class NextFile(
        override val isEnabled: Boolean,
    ) : ViewerActionBarItem {
        override val icon = Icons.AutoMirrored.TwoTone.NavigateNext
        override val label = R.string.viewer_next_file_action.toCaString()
    }

    /**
     * Write streamed content to a place the user picks. The only action a streamed source offers,
     * and what turns it into a real file that supports all the others.
     */
    data object SaveCopy : ViewerActionBarItem {
        override val icon = Icons.TwoTone.SaveAlt
        override val label = R.string.viewer_save_copy_action.toCaString()
    }

    /** Install the APK or app bundle this tab is showing. */
    data object Install : ViewerActionBarItem {
        override val icon = Icons.TwoTone.InstallMobile
        override val label = R.string.viewer_install_action.toCaString()
    }

    /**
     * Open the container in an Explorer tab. Only offered for an archive that lies on the device
     * outside another archive - the two cases that cannot be browsed say so in the page itself.
     */
    data object BrowseArchive : ViewerActionBarItem {
        override val icon = Icons.TwoTone.FolderZip
        override val label = R.string.viewer_browse_archive_action.toCaString()
    }

    /**
     * Hand the file to another app via the system chooser.
     */
    data object OpenWith : ViewerActionBarItem {
        override val icon = Icons.TwoTone.OpenInBrowser
        override val label = R.string.viewer_open_with_action.toCaString()
    }

    /**
     * Send the file to another app via ACTION_SEND.
     */
    data object Share : ViewerActionBarItem {
        override val icon = Icons.TwoTone.Share
        override val label = CommonR.string.general_share_action.toCaString()
    }

    /**
     * Put the file on Butler's clipboard for copying. The paste itself happens in an Explorer tab,
     * exactly as it does for Explorer's own copy action.
     */
    data object Copy : ViewerActionBarItem {
        override val icon = Icons.TwoTone.ContentCopy
        override val label = CommonR.string.general_copy_action.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    /**
     * Put the file on Butler's clipboard for moving. "Cut" rather than "Move" so the staging verb
     * reads the same here as in the Explorer, the Searcher and the clipboard itself; the move is
     * what the paste performs, and that is what the operation is called.
     */
    data object Cut : ViewerActionBarItem {
        override val icon = Icons.TwoTone.ContentCut
        override val label = CommonR.string.general_cut_action.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    /**
     * Open the file's folder in a new Explorer tab. Disabled at a storage root, where there is no
     * parent to show.
     */
    data class OpenLocation(
        override val isEnabled: Boolean = true,
    ) : ViewerActionBarItem {
        override val icon = Icons.AutoMirrored.TwoTone.OpenInNew
        override val label = R.string.viewer_open_location_action.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    /**
     * Delete the file. [trashEnabled] only drives the icon and the destructive tint - whether the
     * file actually reaches the trash is decided by the delete executor, not here.
     */
    data class Delete(
        val trashEnabled: Boolean = false,
    ) : ViewerActionBarItem {
        override val icon = if (trashEnabled) Icons.TwoTone.Delete else Icons.TwoTone.DeleteForever
        override val label = CommonR.string.general_delete_action.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
        override val isDestructive = !trashEnabled
    }
}
