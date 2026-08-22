package eu.darken.butler.common.ipc

import eu.darken.butler.common.ipc.IpcContract.HostIdentity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class IpcContractTest : BaseTest() {

    private val identity = HostIdentity(
        versionCode = 12345,
        versionName = "1.2.3-beta",
        lastUpdateTime = 1755800000000,
        packageCodePath = "/data/app/~~aB1/eu.darken.butler-Xy2/base.apk",
    )

    /** What a host actually answers: the identity frame plus free-form diagnostics below it. */
    private fun reply(firstLine: String) = "$firstLine\nOur pkg: eu.darken.butler\nShell ids are: uid=0"

    @Test
    fun `encoding round-trips`() {
        IpcContract.decode(reply(identity.encode())) shouldBe identity
    }

    @Test
    fun `the frame is a single line`() {
        identity.encode().lines() shouldBe listOf(identity.encode())
    }

    @Test
    fun `a differing versionCode is rejected`() {
        IpcContract.decode(reply(identity.copy(versionCode = 12346).encode())) shouldNotBe identity
    }

    @Test
    fun `a differing versionName is rejected`() {
        IpcContract.decode(reply(identity.copy(versionName = "1.2.4").encode())) shouldNotBe identity
    }

    @Test
    fun `a differing lastUpdateTime alone is rejected`() {
        // The same-version reinstall: every other field agrees, this is the only discriminator.
        val reinstalled = identity.copy(lastUpdateTime = identity.lastUpdateTime + 1)
        IpcContract.decode(reply(reinstalled.encode())) shouldNotBe identity
    }

    @Test
    fun `a differing packageCodePath is rejected`() {
        val moved = identity.copy(packageCodePath = "/data/app/~~cD3/eu.darken.butler-Zz9/base.apk")
        IpcContract.decode(reply(moved.encode())) shouldNotBe identity
    }

    @Test
    fun `a host too old to emit an identity is rejected`() {
        // A pre-identity host answers with a plain diagnostic string, or with the older version marker.
        IpcContract.decode("Our pkg: eu.darken.butler\nShell ids are: uid=0") shouldBe null
        IpcContract.decode(reply("ipc-version: 2")) shouldBe null
    }

    @Test
    fun `a null or empty reply is rejected`() {
        IpcContract.decode(null) shouldBe null
        IpcContract.decode("") shouldBe null
    }

    @Test
    fun `an unstamped host is rejected`() {
        IpcContract.decode(reply(IpcContract.UNSTAMPED)) shouldBe null
    }

    @Test
    fun `the identity only counts on the first line`() {
        IpcContract.decode("Our pkg: eu.darken.butler\n${identity.encode()}") shouldBe null
    }

    @Test
    fun `a truncated frame is rejected`() {
        val encoded = identity.encode()
        // Cut mid-frame (dangling separator) and cut a whole field off the end (missing field)
        IpcContract.decode(encoded.substringBefore("versionName")) shouldBe null
        IpcContract.decode(encoded.substringBeforeLast(";")) shouldBe null
        IpcContract.decode("ipc-host-identity:") shouldBe null
        IpcContract.decode("ipc-host-identity: ") shouldBe null
    }

    @Test
    fun `a duplicated field is rejected`() {
        IpcContract.decode("${identity.encode()};versionCode=12345") shouldBe null
    }

    @Test
    fun `an unknown field is rejected`() {
        IpcContract.decode("${identity.encode()};buildType=debug") shouldBe null
    }

    @Test
    fun `trailing data is rejected`() {
        IpcContract.decode("${identity.encode()};") shouldBe null
        IpcContract.decode("${identity.encode()};garbage") shouldBe null
        // Padding lands inside the last field's value, which is a mismatch rather than a parse error
        IpcContract.decode("${identity.encode()} ") shouldNotBe identity
    }

    @Test
    fun `a non-numeric or out-of-range number is rejected`() {
        IpcContract.decode(identity.encode().replace("versionCode=12345", "versionCode=banana")) shouldBe null
        IpcContract.decode(identity.encode().replace("versionCode=12345", "versionCode=")) shouldBe null
        // Overflows Long, so toLongOrNull() yields null rather than wrapping
        val huge = "versionCode=99999999999999999999999"
        IpcContract.decode(identity.encode().replace("versionCode=12345", huge)) shouldBe null
    }

    @Test
    fun `a bad escape is rejected`() {
        IpcContract.decode(identity.encode().replace("versionName=1.2.3-beta", "versionName=1\\x2")) shouldBe null
        IpcContract.decode(identity.encode().replace("versionName=1.2.3-beta", "versionName=1.2\\")) shouldBe null
    }

    @Test
    fun `a null versionName round-trips as the sentinel`() {
        val unnamed = identity.copy(versionName = IpcContract.VERSION_NAME_UNKNOWN)
        IpcContract.decode(reply(unnamed.encode())) shouldBe unnamed
        IpcContract.decode(reply(unnamed.encode())) shouldNotBe identity
    }

    @Test
    fun `a versionName carrying delimiters or newlines cannot corrupt the frame`() {
        val hostile = identity.copy(versionName = "1.0;versionCode=666=\\\nsecond line\rthird")
        val encoded = hostile.encode()

        encoded.lines() shouldBe listOf(encoded)
        IpcContract.decode(reply(encoded)) shouldBe hostile
    }
}
