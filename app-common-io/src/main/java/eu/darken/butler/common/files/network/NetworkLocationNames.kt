package eu.darken.butler.common.files.network

import androidx.annotation.VisibleForTesting
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.smb.location.SmbLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * The saved network locations' names, readable synchronously so a path can render itself:
 * `sftp://<id>/usr/bin` is shown as `sftp://cnc-dev/usr/bin`. An id without a name (deleted, or not
 * loaded yet) returns null.
 *
 * SMB and SFTP ids are separate namespaces. [NetworkLocationNamesUpdater] keeps both current.
 * [snapshot] is the same state as a flow, for UI that has to redraw when a name arrives or changes.
 */
object NetworkLocationNames {

    data class Snapshot(
        val smb: Map<Uuid, CaString> = emptyMap(),
        val sftp: Map<Uuid, CaString> = emptyMap(),
    )

    private val state = MutableStateFlow(Snapshot())

    val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    fun smb(id: Uuid): CaString? = state.value.smb[id]

    fun sftp(id: Uuid): CaString? = state.value.sftp[id]

    fun updateSmb(locations: Collection<SmbLocation>) {
        val names = locations.associate { it.id to it.displayName }
        state.update { it.copy(smb = names) }
    }

    fun updateSftp(locations: Collection<SftpLocation>) {
        val names = locations.associate { it.id to it.displayName }
        state.update { it.copy(sftp = names) }
    }

    @VisibleForTesting
    fun clear() {
        state.value = Snapshot()
    }
}
