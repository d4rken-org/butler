package eu.darken.butler.common.adb.shizuku

import android.content.Context
import eu.darken.porter.sdk.PorterBackend
import eu.darken.porter.sdk.PorterConnection
import eu.darken.porter.sdk.PorterConnectionState
import eu.darken.porter.sdk.PorterIncompatibility
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PorterServerSourceTest {

    private val porterState = MutableStateFlow<PorterConnectionState>(PorterConnectionState.Disconnected)
    private val source = PorterServerSource(mockk<Context>(), porterState)

    private fun connection(backend: PorterBackend) = mockk<PorterConnection>().also {
        every { it.backend } returns backend
        every { it.permission } returns mockk()
    }

    private fun connected(connection: PorterConnection) = mockk<PorterConnectionState.Connected>().also {
        every { it.connection } returns connection
    }

    private fun incompatible(backend: PorterBackend, serverTooOld: Boolean, clientTooOld: Boolean) =
        mockk<PorterConnectionState.Incompatible>().also { state ->
            val reason = mockk<PorterIncompatibility>()
            every { reason.backend } returns backend
            every { reason.serverTooOld } returns serverTooOld
            every { reason.clientTooOld } returns clientTooOld
            every { state.incompatibility } returns reason
        }

    @Test
    fun `disconnected maps to no current server`() = runTest {
        source.state.first() shouldBe AdbConnectionState.Disconnected
        source.current() shouldBe null
    }

    @Test
    fun `connected maps both backends and current reads the same flow`() = runTest {
        for ((sdkBackend, backend) in listOf(
            PorterBackend.PORTER to AdbBackend.PORTER,
            PorterBackend.SHIZUKU to AdbBackend.SHIZUKU,
        )) {
            porterState.value = connected(connection(sdkBackend))

            val mapped = source.state.first().shouldBeInstanceOf<AdbConnectionState.Connected>()
            mapped.server.backend shouldBe backend
            mapped.server shouldBe AdbServer(source.current()!!)

            porterState.value = PorterConnectionState.Disconnected
            source.current() shouldBe null
            source.state.first() shouldBe AdbConnectionState.Disconnected
        }
    }

    @Test
    fun `incompatibility maps every backend and update flag combination without a current server`() = runTest {
        for ((sdkBackend, backend) in listOf(
            PorterBackend.PORTER to AdbBackend.PORTER,
            PorterBackend.SHIZUKU to AdbBackend.SHIZUKU,
        )) {
            for (serverTooOld in listOf(false, true)) {
                for (clientTooOld in listOf(false, true)) {
                    porterState.value = connected(connection(sdkBackend))
                    source.current() shouldNotBe null
                    porterState.value = incompatible(sdkBackend, serverTooOld, clientTooOld)

                    source.state.first() shouldBe AdbConnectionState.Incompatible(backend, serverTooOld, clientTooOld)
                    source.current() shouldBe null
                }
            }
        }
    }

    @Test
    fun `mapped connected equality preserves SDK connection identity`() = runTest {
        val sdkConnection = connection(PorterBackend.PORTER)
        porterState.value = connected(sdkConnection)
        val first = source.state.first().shouldBeInstanceOf<AdbConnectionState.Connected>()
        porterState.value = connected(sdkConnection)
        source.state.first() shouldBe first
        AdbServer(source.current()!!) shouldBe first.server

        porterState.value = connected(connection(PorterBackend.PORTER))
        val replacement = source.state.first().shouldBeInstanceOf<AdbConnectionState.Connected>()
        replacement shouldNotBe first
        replacement.server shouldNotBe first.server
        AdbServer(source.current()!!) shouldBe replacement.server
    }
}
