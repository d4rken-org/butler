package eu.darken.butler.common.files.webdav.credentials

import eu.darken.webdav.WebDavCredentials

/** A resolved credential, handed out as a private copy the caller may wipe. */
sealed class WebDavCredential {

    abstract val username: String

    abstract fun wipe()

    /** Shares this credential's arrays, so [wipe] only after the connection attempt returned. */
    abstract fun toWebDavCredentials(): WebDavCredentials.Basic

    internal abstract fun copy(): WebDavCredential

    class Password(
        override val username: String,
        val password: CharArray,
    ) : WebDavCredential() {
        override fun wipe() = password.fill(Char(0))

        override fun toWebDavCredentials() = WebDavCredentials.Basic(username, password)

        override fun copy() = Password(username, password.copyOf())

        override fun toString(): String = "WebDavCredential.Password(<redacted>)"
    }
}
