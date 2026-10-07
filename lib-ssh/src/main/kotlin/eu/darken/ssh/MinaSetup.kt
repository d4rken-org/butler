package eu.darken.ssh

import java.nio.file.Path
import java.nio.file.Paths
import java.security.Provider
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.common.util.security.bouncycastle.BouncyCastleSecurityProviderRegistrar
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Process-wide Apache MINA SSHD state. Every MINA entry point calls [install] before it uses any other
 * MINA class, including test code that runs MINA's server in the same process.
 *
 * MINA resolves JCA providers through registrars, loaded once per process on its first crypto lookup
 * from the system property `org.apache.sshd.security.registrars`. The stock registrars add Bouncy
 * Castle to the global provider list, and on Android they fail (`No EC params for nistp384`). [install]
 * sets that property to `none` for the whole process before MINA reads it, then registers
 * [PrivateBouncyCastle]: MINA calls `getInstance(algorithm, provider)` with a private
 * [BouncyCastleProvider] and never `Security.addProvider`. Ciphers and MACs stay with the platform's
 * providers. If MINA's registration already ran with other registrars, [install] fails instead.
 *
 * MINA also derives `~/.ssh` defaults from the user home, and on Android, where `user.home` is empty,
 * fails in static initializers without a resolver. The home is set to `/dev/null`: nothing below a
 * character device exists or can be created, so every such default is empty.
 */
internal object MinaSetup {
    private const val REGISTRARS_PROPERTY = "org.apache.sshd.security.registrars"
    private val NO_HOME: Path = Paths.get("/dev/null")

    fun install() = Unit

    init {
        System.setProperty(REGISTRARS_PROPERTY, "none")
        val registrar = PrivateBouncyCastle()
        registrar.properties[registrar.getConfigurationPropertyName("Cipher")] = "none"
        registrar.properties[registrar.getConfigurationPropertyName("Mac")] = "none"
        check(SecurityUtils.registerSecurityProvider(registrar) === registrar) {
            "MINA already uses another Bouncy Castle registrar"
        }
        // Completes MINA's one-time registration, which now reads "none".
        SecurityUtils.isBouncyCastleRegistered()
        check(SecurityUtils.getRegisteredProviders() == setOf(registrar.name)) {
            "MINA registered other providers: ${SecurityUtils.getRegisteredProviders()}"
        }
        PathUtils.setUserHomeFolderResolver { NO_HOME }
    }

    private class PrivateBouncyCastle : BouncyCastleSecurityProviderRegistrar() {
        private val provider by lazy { BouncyCastleProvider() }

        override fun getSecurityProvider(): Provider = provider

        override fun isNamedProviderUsed(): Boolean = false
    }
}
