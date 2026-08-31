package eu.darken.butler.setup.ui.items

import eu.darken.butler.setup.core.root.RootServiceState
import eu.darken.butler.setup.core.root.RootSetupModule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The card's headline, its sub-line and its icon each render the same decision, so the decision is
 * made once, here, rather than re-derived per surface.
 */
class RootServiceStatusTest : BaseTest() {

    private fun result(
        useRoot: Boolean?,
        isInstalled: Boolean,
        serviceState: RootServiceState,
    ) = RootSetupModule.Result(
        useRoot = useRoot,
        isInstalled = isInstalled,
        serviceState = serviceState,
    )

    @Test fun `root not configured is disabled`() {
        result(null, isInstalled = true, RootServiceState.Available).toCardStatus() shouldBe RootCardStatus.DISABLED
        result(null, isInstalled = false, RootServiceState.Failed).toCardStatus() shouldBe RootCardStatus.DISABLED
    }

    @Test fun `root turned off is disabled`() {
        result(false, isInstalled = true, RootServiceState.Available).toCardStatus() shouldBe RootCardStatus.DISABLED
        result(false, isInstalled = false, RootServiceState.Failed).toCardStatus() shouldBe RootCardStatus.DISABLED
    }

    @Test fun `no state at all is disabled`() {
        val noState: RootSetupModule.Result? = null
        noState.toCardStatus() shouldBe RootCardStatus.DISABLED
    }

    /**
     * The reported defect: a rooted device whose root manager is none of the package ids we can look
     * up. The service is answering, so that is what the card has to say.
     */
    @Test fun `an answering service outranks an unknown root manager`() {
        result(true, isInstalled = false, RootServiceState.Available).toCardStatus() shouldBe RootCardStatus.CONNECTED
    }

    @Test fun `an answering service with a known manager is connected`() {
        result(true, isInstalled = true, RootServiceState.Available).toCardStatus() shouldBe RootCardStatus.CONNECTED
    }

    /** The handshake window: nothing has concluded yet, so nothing may be reported as missing. */
    @Test fun `a pending probe is connecting`() {
        result(true, isInstalled = true, RootServiceState.Connecting).toCardStatus() shouldBe RootCardStatus.CONNECTING
        result(true, isInstalled = false, RootServiceState.Connecting).toCardStatus() shouldBe RootCardStatus.CONNECTING
    }

    @Test fun `an unprobed service is connecting`() {
        result(true, isInstalled = true, RootServiceState.NotChecked).toCardStatus() shouldBe RootCardStatus.CONNECTING
    }

    @Test fun `a failed probe without a known manager is not installed`() {
        result(true, isInstalled = false, RootServiceState.Failed).toCardStatus() shouldBe RootCardStatus.NOT_INSTALLED
    }

    @Test fun `a failed probe with a known manager is not connected`() {
        result(true, isInstalled = true, RootServiceState.Failed).toCardStatus() shouldBe RootCardStatus.NOT_CONNECTED
    }

    /**
     * A timeout says nothing about the manager, only that the handshake never finished, so the
     * installed-manager lookup does not get to change the answer here.
     */
    @Test fun `a handshake that ran out of time is a connection failure`() {
        result(true, isInstalled = true, RootServiceState.TimedOut)
            .toCardStatus() shouldBe RootCardStatus.CONNECTION_FAILED
        result(true, isInstalled = false, RootServiceState.TimedOut)
            .toCardStatus() shouldBe RootCardStatus.CONNECTION_FAILED
    }
}
