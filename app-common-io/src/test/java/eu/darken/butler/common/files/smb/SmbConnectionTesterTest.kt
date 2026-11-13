package eu.darken.butler.common.files.smb

import eu.darken.smb.SmbConnector
import eu.darken.smb.SmbCredentials
import eu.darken.smb.SmbException
import eu.darken.smb.SmbException.Kind
import eu.darken.smb.SmbServer
import eu.darken.smb.SmbShareInfo
import eu.darken.smb.SmbShareLister
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SmbConnectionTesterTest : BaseTest() {

    private class FakeClientFactory : SmbClientFactory {
        var listResult: List<SmbShareInfo> = emptyList()
        var listError: Exception? = null
        val listings = mutableListOf<Pair<SmbServer, SmbCredentials>>()
        var connects = 0

        override fun create(): SmbConnector = SmbConnector { _, _ ->
            connects++
            throw IllegalStateException("A server entry test must not connect to a share")
        }

        override fun createLister(): SmbShareLister = SmbShareLister { server, credentials ->
            listings.add(server to credentials)
            listError?.let { throw it }
            listResult
        }
    }

    private val factory = FakeClientFactory()
    private val tester = SmbConnectionTester(factory)

    private suspend fun testServer(username: String? = "darken", password: CharArray? = "hunter2".toCharArray()) =
        tester.test(
            host = "nas.local",
            port = 4455,
            share = null,
            username = username,
            domain = "WORKGROUP",
            password = password,
        )

    @Test
    fun `a server entry is tested by listing its shares`() = runTest {
        testServer()

        val (server, credentials) = factory.listings.single()
        server shouldBe SmbServer("nas.local", 4455)
        credentials.shouldBeInstanceOf<SmbCredentials.Password>().apply {
            username shouldBe "darken"
            domain shouldBe "WORKGROUP"
        }
        factory.connects shouldBe 0
    }

    @Test
    fun `a server without any share still passes`() = runTest {
        factory.listResult = emptyList()

        testServer()
    }

    @Test
    fun `a server entry without a password is tested as guest`() = runTest {
        testServer(username = null, password = null)

        factory.listings.single().second shouldBe SmbCredentials.Guest
    }

    @Test
    fun `a rejected sign-in is an auth failure`() = runTest {
        factory.listError = SmbException(Kind.AUTHENTICATION)

        shouldThrow<SmbAuthException> { testServer() }.endpoint shouldBe "nas.local:4455"
    }

    @Test
    fun `a refused listing asks for a share name instead of a new password`() = runTest {
        factory.listError = SmbException(Kind.ACCESS_DENIED)

        val error = shouldThrow<SmbShareListingUnavailableException> { testServer() }

        error.endpoint shouldBe "nas.local:4455"
        error.isSmbSignInFailure() shouldBe false
    }

    @Test
    fun `an unreachable server is unreachable`() = runTest {
        factory.listError = SmbException(Kind.TRANSPORT)

        shouldThrow<SmbUnreachableException> { testServer() }
    }

    @Test
    fun `an SMB1-only server is reported as such`() = runTest {
        factory.listError = SmbException(Kind.UNSUPPORTED_DIALECT)

        shouldThrow<SmbDialectNotSupportedException> { testServer() }
    }
}
