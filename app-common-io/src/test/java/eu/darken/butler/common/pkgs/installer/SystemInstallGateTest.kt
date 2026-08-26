package eu.darken.butler.common.pkgs.installer

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SystemInstallGateTest : BaseTest() {

    @Test
    fun `a second claim is refused and names the holder`() {
        val gate = SystemInstallGate()

        gate.claim("First App").shouldBeInstanceOf<SystemInstallGate.Outcome.Granted>()

        gate.claim("Second App")
            .shouldBeInstanceOf<SystemInstallGate.Outcome.Busy>()
            .label shouldBe "First App"
    }

    @Test
    fun `releasing hands the installer to the next claim`() {
        val gate = SystemInstallGate()
        val first = gate.claim("First App").shouldBeInstanceOf<SystemInstallGate.Outcome.Granted>().claim

        gate.release(first)

        gate.claim("Second App").shouldBeInstanceOf<SystemInstallGate.Outcome.Granted>()
    }

    @Test
    fun `a stale claim cannot release the installer from under its holder`() {
        val gate = SystemInstallGate()
        val first = gate.claim("First App").shouldBeInstanceOf<SystemInstallGate.Outcome.Granted>().claim
        gate.release(first)
        val second = gate.claim("Second App").shouldBeInstanceOf<SystemInstallGate.Outcome.Granted>().claim

        gate.release(first)

        gate.claim("Third App")
            .shouldBeInstanceOf<SystemInstallGate.Outcome.Busy>()
            .label shouldBe "Second App"
        gate.release(second)
    }
}
