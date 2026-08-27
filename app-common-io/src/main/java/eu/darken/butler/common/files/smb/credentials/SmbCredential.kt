package eu.darken.butler.common.files.smb.credentials

/** A resolved credential, handed out as a private copy the caller may wipe. */
class SmbCredential(
    val username: String,
    val domain: String?,
    val password: CharArray,
) {
    fun wipe() = password.fill(Char(0))
}
