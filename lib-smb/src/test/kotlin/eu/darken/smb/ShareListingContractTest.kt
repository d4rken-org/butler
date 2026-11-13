package eu.darken.smb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Kotlin-only: SMBJ has no share listing to compare against. */
@Tag("smb-server")
internal class ShareListingContractTest {
    private fun password() = SmbCredentials.Password("butler", "butlerpass".toCharArray())

    /** `private` and `restricted` are `browseable = no` in the rig, which Samba omits. */
    private fun assertRigShares(shares: List<SmbShareInfo>) {
        val byName = shares.associateBy { it.name }
        for (name in listOf("public", "readonly")) {
            assertEquals(SmbShareInfo.Type.DISK, byName.getValue(name).type, name)
            assertFalse(byName.getValue(name).special, name)
        }
        assertEquals(SmbShareInfo.Type.IPC, byName.getValue("IPC$").type)
        assertTrue(byName.getValue("IPC$").special)
        assertFalse("private" in byName)
        assertFalse("restricted" in byName)
    }

    @Test
    fun `password listing reports disk shares and IPC`() = runBlocking {
        assertRigShares(KotlinSmbClient().listShares(server.server(), password()))
    }

    @ParameterizedTest
    @EnumSource(SmbDialect::class)
    fun `listing works on every dialect`(dialect: SmbDialect) = runBlocking {
        val config = SmbConfig(dialects = setOf(dialect), requireSigning = true)
        assertRigShares(KotlinSmbClient(config).listShares(server.server(), password()))
    }

    @Test
    fun `listing works when the server mandates signing`() = runBlocking {
        assertRigShares(KotlinSmbClient().listShares(secureServer.server(), password()))
    }

    @Test
    fun `listing works when the server requires encryption`() = runBlocking {
        assertRigShares(KotlinSmbClient().listShares(encryptedServer.server(), password()))
    }

    @Test
    fun `guest listing is allowed by the rig`() = runBlocking {
        assertRigShares(KotlinSmbClient().listShares(server.server(), SmbCredentials.Guest))
    }

    @Test
    fun `wrong password fails authentication`() = runBlocking<Unit> {
        assertFailure(SmbException.Kind.AUTHENTICATION) {
            KotlinSmbClient()
                .listShares(
                    server.server(),
                    SmbCredentials.Password("butler", "wrong".toCharArray()),
                )
        }
    }

    @Test
    fun `share connection to IPC is still rejected as not a disk share`() = runBlocking {
        val error =
            assertFailure(SmbException.Kind.PROTOCOL) {
                KotlinSmbClient().connect(server.endpoint("IPC$"), password())
            }
        assertEquals("Not a disk share", error.message)
    }

    @Test
    fun `SMB1 only server is identified`() = runBlocking<Unit> {
        SambaServer(legacy = true).use {
            it.start()
            assertFailure(SmbException.Kind.UNSUPPORTED_DIALECT) {
                KotlinSmbClient().listShares(it.server(), password())
            }
        }
    }

    private suspend fun assertFailure(
        kind: SmbException.Kind,
        block: suspend () -> Unit,
    ): SmbException {
        try {
            block()
        } catch (error: SmbException) {
            assertEquals(kind, error.kind, error.message)
            return error
        }
        fail<Unit>("Expected $kind")
        error("unreachable")
    }

    companion object {
        private val server: SambaServer by lazy { SambaServer().also { it.start() } }
        private val secureServer: SambaServer by lazy {
            SambaServer(secure = true).also { it.start() }
        }
        private val encryptedServer: SambaServer by lazy {
            SambaServer(encrypt = true).also { it.start() }
        }
    }
}
