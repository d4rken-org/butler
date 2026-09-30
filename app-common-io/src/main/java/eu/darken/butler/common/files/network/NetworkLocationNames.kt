package eu.darken.butler.common.files.network

import androidx.annotation.VisibleForTesting
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.smb.location.SmbLocation
import kotlin.uuid.Uuid

/**
 * The saved network locations' names, readable synchronously so a path can render itself:
 * `sftp://<id>/usr/bin` is shown as `sftp://cnc-dev/usr/bin`. An id without a name (deleted, or not
 * loaded yet) returns null.
 *
 * SMB and SFTP ids are separate namespaces. [NetworkLocationNamesUpdater] keeps both current.
 */
object NetworkLocationNames {

    @Volatile private var smb: Map<Uuid, CaString> = emptyMap()
    @Volatile private var sftp: Map<Uuid, CaString> = emptyMap()

    fun smb(id: Uuid): CaString? = smb[id]

    fun sftp(id: Uuid): CaString? = sftp[id]

    fun updateSmb(locations: Collection<SmbLocation>) {
        smb = locations.associate { it.id to it.displayName }
    }

    fun updateSftp(locations: Collection<SftpLocation>) {
        sftp = locations.associate { it.id to it.displayName }
    }

    @VisibleForTesting
    fun clear() {
        smb = emptyMap()
        sftp = emptyMap()
    }
}
