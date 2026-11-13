package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.smb.SmbLocationInput
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.webdav.WebDavLocationInput
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.explorer.R
import java.net.URI

/**
 * Saved paths below a network location are its id plus segments relative to where it starts, so an
 * edit that moves the start repoints favorites, shortcuts and the like.
 */
@Composable
fun NetworkRootChangeWarning(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.TwoTone.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.explorer_network_form_root_change_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * ```
 * share "media", folder ["movies"]:
 *   startsElsewhere(share = "/media/", basePath = "movies/")  -> false
 *   startsElsewhere(share = "media", basePath = "")           -> true
 *   startsElsewhere(share = "", basePath = "")                -> true, now a server entry
 * ```
 */
fun SmbLocation.startsElsewhere(share: String, basePath: String): Boolean {
    val typedShare = share.trim().trim('\\', '/').takeIf { it.isNotEmpty() }
    return typedShare != this.share || SmbLocationInput.splitPath(basePath) != this.basePath
}

/** The base path is stored as typed, the server resolves it. */
fun SftpLocation.startsElsewhere(basePath: String): Boolean = basePath.trim() != this.basePath.trim()

/**
 * Only the path counts, a URL that does not parse yet counts as unchanged.
 * ```
 * saved https://cloud.example.com/dav/:
 *   startsElsewhere("https://cloud.example.com/dav")       -> false
 *   startsElsewhere("http://nas.local:8080/dav/")          -> false
 *   startsElsewhere("https://cloud.example.com/dav/alice") -> true
 * ```
 */
fun WebDavLocation.startsElsewhere(url: String): Boolean {
    val typed = WebDavLocationInput.parse(
        url = url,
        label = "",
        authType = WebDavLocation.AuthType.ANONYMOUS,
        username = "",
    ) as? WebDavLocationInput.Result.Valid ?: return false
    return pathSegmentsOf(typed.parsed.url) != pathSegmentsOf(this.url)
}

private fun pathSegmentsOf(url: String) = WebDavLocationInput.splitPath(URI(url).path)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun NetworkRootChangeWarningPreview() {
    NetworkRootChangeWarning()
}
