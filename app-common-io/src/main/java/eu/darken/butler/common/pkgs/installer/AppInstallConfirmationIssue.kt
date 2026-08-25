package eu.darken.butler.common.pkgs.installer

import android.content.Intent
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.io.R
import eu.darken.butler.common.issue.Issue

/**
 * The platform installer is waiting for the user to confirm this install.
 *
 * Android discards a confirmation activity started by an app that is in the background and nothing
 * re-sends it, so [confirmIntent] is carried here: launching it again is what resolves this.
 */
data class AppInstallConfirmationIssue(
    override val id: Issue.Id = Issue.Id(),
    /** What is being installed, for the sheet to name. Null when the container never said. */
    val label: String?,
    val confirmIntent: Intent,
) : Issue {

    override val title: CaString = R.string.app_install_confirm_pending_title.toCaString()

    override val description: CaString = caString {
        when (label) {
            null -> it.getString(R.string.app_install_confirm_pending_description_unnamed)
            else -> it.getString(R.string.app_install_confirm_pending_description, label)
        }
    }
}
