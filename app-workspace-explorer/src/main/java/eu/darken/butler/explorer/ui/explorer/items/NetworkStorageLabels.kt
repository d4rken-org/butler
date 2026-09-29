package eu.darken.butler.explorer.ui.explorer.items

import android.content.Context
import eu.darken.butler.common.files.network.NetworkEndpointState
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import kotlin.time.Instant

/**
 * A credential problem outranks reachability: it is the one the user can act on.
 *
 * The resolved address is deliberately not part of any row. Whoever typed a hostname knows it, and
 * whoever typed an address already sees it in the row above; the address belongs to the info sheet.
 *
 * [now] is passed in rather than sampled here, so the "ago" a caller renders is the one it can keep
 * up to date, and so the wording is testable against a fixed reference.
 */
internal fun ExplorerItem.Storage.Network.statusLabel(context: Context, now: Instant): String = when (status) {
    ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED -> {
        context.getString(R.string.explorer_network_sign_in_required_label)
    }

    ExplorerItem.Storage.Network.Status.AVAILABLE -> when (endpoint.reachability) {
        NetworkEndpointState.Reachability.CHECKING -> context.getString(R.string.explorer_network_status_checking_label)
        NetworkEndpointState.Reachability.REACHABLE -> context.getString(R.string.explorer_network_status_available_label)
        // A location that was never reached has no "ago" to state.
        NetworkEndpointState.Reachability.UNREACHABLE -> location.lastSeenAt
            ?.let {
                context.getString(
                    R.string.explorer_network_status_unavailable_since_format,
                    formatRelativeTime(context, it, now),
                )
            }
            ?: context.getString(R.string.explorer_network_status_unavailable_label)
    }
}

/**
 * The base path as the user typed it: `""` is the server's initial folder, `/srv/media` is shown
 * as-is, `media` is marked as relative to the initial folder.
 */
internal fun SftpLocation.basePathLabel(context: Context): String = when {
    basePath.isEmpty() -> context.getString(R.string.explorer_info_sftp_folder_initial)
    basePath.startsWith("/") -> basePath
    else -> context.getString(R.string.explorer_info_sftp_folder_relative_format, basePath)
}
