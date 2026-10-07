package eu.darken.ssh

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Key parsing happens before any network I/O, so these run without a server. */
internal class KeyLoadingTest {
    private fun load(name: String, passphrase: CharArray?) {
        MinaSetup.install()
        Handshake.of(HostKeyPolicy.Unknown, SshCredentials.PrivateKey("user", TestKeys.bytes(name), passphrase))
    }

    @ParameterizedTest
    @CsvSource(
        "user_ed25519, ed25519-passphrase",
        "user_rsa_pem, rsa-passphrase",
    )
    fun `encrypted keys load with their passphrase`(name: String, passphrase: String) {
        load(name, passphrase.toCharArray())
    }

    /** Android ships no `javax.security.auth.login`: resolving one of its classes there is an Error. */
    @ParameterizedTest
    @CsvSource(
        "user_ed25519, wrong-passphrase",
        "user_ed25519, ''",
        "user_ed25519, ",
        "user_rsa_pem, wrong-passphrase",
        "user_rsa_pem, ''",
        "user_rsa_pem, ",
    )
    fun `wrong empty or missing passphrase is reported without javax security auth login`(
        name: String,
        passphrase: String?,
    ) {
        val error = assertThrows(SshException::class.java) { load(name, passphrase?.toCharArray()) }
        assertEquals(SshException.Kind.KEY_PASSPHRASE, error.kind)
        val chain = generateSequence<Throwable>(error) { it.cause }.map { it.javaClass.name }.toList()
        assertEquals(emptyList<String>(), chain.filter { it.startsWith("javax.security.auth.login.") })
    }
}
