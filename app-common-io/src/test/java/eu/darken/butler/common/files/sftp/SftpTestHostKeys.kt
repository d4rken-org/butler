package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.ssh.HostKey

/** A well-formed `ssh-ed25519` key whose 32 key bytes are all [seed]; different seeds, different keys. */
fun testHostKey(seed: Int): TrustedHostKey {
    val type = "ssh-ed25519".encodeToByteArray()
    val blob = byteArrayOf(0, 0, 0, type.size.toByte()) + type + byteArrayOf(0, 0, 0, 32) + ByteArray(32) { seed.toByte() }
    return TrustedHostKey.from(HostKey("ssh-ed25519", blob))
}
