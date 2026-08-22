package eu.darken.butler.common.ipc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Covers what the service clients do with the identity a host echoes back: the fresh-connection gate,
 * its single bounded reconnect, and that the stale host's teardown is finished before the reconnect.
 *
 * The upstream here stands in for RootHostLauncher/AdbHostLauncher: one host per collection, with the
 * bind/unbind/await-disconnect breadcrumbs those launchers emit in their teardown.
 */
class IpcHostIdentityGateTest : BaseTest() {

    private val ours = IpcContract.HostIdentity(
        versionCode = 12345,
        versionName = "1.2.3",
        lastUpdateTime = 1755800000000,
        packageCodePath = "/data/app/~~aB1/eu.darken.butler-Xy2/base.apk",
    )

    /** A host left over from the previous installation: same version, earlier install timestamp. */
    private val stale = ours.copy(lastUpdateTime = ours.lastUpdateTime - 5000)

    private val events = mutableListOf<String>()
    private var launches = 0

    /** Emits one "host" (its index) per collection and tears it down like the real launchers do. */
    private fun hosts(): Flow<Int> = callbackFlow {
        val host = launches++
        events += "bind#$host"
        send(host)
        try {
            awaitClose { }
        } finally {
            withContext(NonCancellable) {
                events += "unbind#$host"
                events += "awaitDisconnect#$host"
            }
        }
    }

    private fun gate(vararg replies: String?) = hosts().gateOnHostIdentity(
        tag = "test",
        expected = { ours },
        checkBase = { host -> replies[minOf(host, replies.size - 1)] },
        onAccepted = { host, identity -> "connection#$host(${identity.lastUpdateTime})" },
    )

    @Test fun `a matching identity is handed straight through`() = runTest {
        gate(ours.encode()).first() shouldBe "connection#0(${ours.lastUpdateTime})"
        launches shouldBe 1
    }

    @Test fun `a stale host is torn down and replaced, once`() = runTest {
        gate(stale.encode(), ours.encode()).first() shouldBe "connection#1(${ours.lastUpdateTime})"

        launches shouldBe 2
        // The stale host must be gone before the replacement binds: Shizuku keys its late unbind on
        // the service args, so an unbind still in flight can remove the newer generation.
        events shouldContainInOrder listOf("bind#0", "unbind#0", "awaitDisconnect#0", "bind#1")
    }

    @Test fun `a host that stays stale fails without looping`() = runTest {
        shouldThrow<IpcContractMismatchException> { gate(stale.encode()).first() }

        launches shouldBe 2
        events shouldContainInOrder listOf("unbind#0", "bind#1", "unbind#1")
    }

    @Test fun `a host too old to answer with an identity is a mismatch`() = runTest {
        gate("ipc-version: 2\nOur pkg: eu.darken.butler", ours.encode()).first() shouldBe
            "connection#1(${ours.lastUpdateTime})"

        launches shouldBe 2
    }

    @Test fun `an unrelated failure is not retried`() = runTest {
        val boom = IllegalStateException("host went away")
        val flow = hosts().gateOnHostIdentity<Int, String>(
            tag = "test",
            expected = { ours },
            checkBase = { throw boom },
            onAccepted = { _, _ -> "unreachable" },
        )

        shouldThrow<IllegalStateException> { flow.first() } shouldBe boom
        launches shouldBe 1
    }
}
