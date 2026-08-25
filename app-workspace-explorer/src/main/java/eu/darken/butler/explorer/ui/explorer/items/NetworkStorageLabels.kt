package eu.darken.butler.explorer.ui.explorer.items

import android.content.Context
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem

/**
 * A credential problem outranks reachability: it is the one the user can act on.
 *
 * The resolved address is deliberately not part of any row. Whoever typed a hostname knows it, and
 * whoever typed an address already sees it in the row above; the address belongs to the info sheet.
 */
internal fun ExplorerItem.Storage.Network.statusLabel(context: Context): String = when (status) {
    ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED -> {
        context.getString(R.string.explorer_network_sign_in_required_label)
    }

    ExplorerItem.Storage.Network.Status.AVAILABLE -> when (endpoint.reachability) {
        SmbEndpointState.Reachability.CHECKING -> context.getString(R.string.explorer_network_status_checking_label)
        SmbEndpointState.Reachability.REACHABLE -> context.getString(R.string.explorer_network_status_available_label)
        SmbEndpointState.Reachability.UNREACHABLE -> {
            context.getString(R.string.explorer_network_status_unavailable_label)
        }
    }
}
