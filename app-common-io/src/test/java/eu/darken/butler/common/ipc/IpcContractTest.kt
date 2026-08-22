package eu.darken.butler.common.ipc

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class IpcContractTest : BaseTest() {

    private fun reply(firstLine: String) = "$firstLine\nOur pkg: eu.darken.butler\nShell ids are: uid=0"

    @Test
    fun `our own marker is accepted`() {
        IpcContract.isCompatible(reply(IpcContract.marker())) shouldBe true
    }

    @Test
    fun `a host too old to emit a marker is rejected`() {
        // Exactly what a pre-handshake host answers: a plain diagnostic string.
        IpcContract.isCompatible("Our pkg: eu.darken.butler\nShell ids are: uid=0") shouldBe false
    }

    @Test
    fun `a null reply is rejected`() {
        IpcContract.isCompatible(null) shouldBe false
    }

    @Test
    fun `a different version is rejected`() {
        IpcContract.isCompatible(reply("ipc-version: ${IpcContract.VERSION + 1}")) shouldBe false
        IpcContract.isCompatible(reply("ipc-version: ${IpcContract.VERSION - 1}")) shouldBe false
    }

    @Test
    fun `a malformed or out-of-range version is rejected`() {
        IpcContract.isCompatible(reply("ipc-version: banana")) shouldBe false
        IpcContract.isCompatible(reply("ipc-version:")) shouldBe false
        // Overflows Int, so toIntOrNull() yields null rather than wrapping
        IpcContract.isCompatible(reply("ipc-version: 99999999999999999999")) shouldBe false
    }

    @Test
    fun `the marker only counts on the first line`() {
        IpcContract.isCompatible("Our pkg: eu.darken.butler\n${IpcContract.marker()}") shouldBe false
    }

    @Test
    fun `more than one marker is rejected`() {
        IpcContract.isCompatible("${IpcContract.marker()}\n${IpcContract.marker()}") shouldBe false
    }

    @Test
    fun `an empty reply is rejected`() {
        IpcContract.isCompatible("") shouldBe false
    }
}
