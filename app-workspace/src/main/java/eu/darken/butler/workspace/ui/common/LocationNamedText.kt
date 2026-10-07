package eu.darken.butler.workspace.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.network.NetworkLocationNames

/**
 * [CaString.get] for text that can name a network location, e.g. `sftp://cnc-dev/usr/bin` or a
 * location root's `cnc-dev`. Unlike a plain `get` in composition, it redraws when the name arrives
 * after the first frame or the location is renamed.
 */
@Composable
fun CaString.withLocationNames(): String {
    // Reading the state is what subscribes this composition to name changes.
    NetworkLocationNames.snapshot.collectAsState().value
    return get(LocalContext.current)
}
