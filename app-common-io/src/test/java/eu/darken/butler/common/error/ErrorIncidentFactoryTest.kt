package eu.darken.butler.common.error

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.RingLogBuffer
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.io.IOException
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class ErrorIncidentFactoryTest : BaseTest() {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val spoolDir: File
        get() = File(File(app.cacheDir, ErrorReportPackager.REPORTS_DIR), "incidents")

    private val ringLogBuffer = RingLogBuffer()

    private fun value(source: Flow<Boolean?>) =
        mockk<DataStoreValue<Boolean?>>().apply { every { this@apply.flow } returns source }

    private fun factory(
        rootConsent: Flow<Boolean?> = flowOf(true),
        adbConsent: Flow<Boolean?> = flowOf(false),
        lastKnownRooted: Boolean? = true,
        lastShizukudResult: Boolean? = null,
    ) = ErrorIncidentFactory(
        context = app,
        ringLogBuffer = ringLogBuffer,
        dispatcherProvider = TestDispatcherProvider(),
        rootSettings = mockk<RootSettings>().apply { every { useRoot } returns value(rootConsent) },
        adbSettings = mockk<AdbSettings>().apply { every { useShizuku } returns value(adbConsent) },
        rootManager = mockk<RootManager>().apply { every { this@apply.lastKnownRooted } returns lastKnownRooted },
        shizukuManager = mockk<ShizukuManager>().apply {
            every { this@apply.lastShizukudResult } returns lastShizukudResult
        },
    )

    @Before
    fun setup() {
        spoolDir.deleteRecursively()
        ringLogBuffer.clear()
    }

    @Test
    fun `the log trail is spooled and referenced`() = runTest {
        ringLogBuffer.log(Logging.Priority.INFO, "Test", "something happened", null)

        val incident = factory().freeze(IOException("boom"))

        incident.logFile shouldNotBe null
        incident.logFile!!.name shouldBe "${incident.incidentId}.log"
        incident.logFile!!.readText() shouldContain "something happened"
    }

    @Test
    fun `the access state is merged into the context`() = runTest {
        val incident = factory(lastKnownRooted = false, lastShizukudResult = true).freeze(IOException("boom"))

        incident.context["access.root.consent"] shouldBe "true"
        incident.context["access.root.lastKnown"] shouldBe "false"
        incident.context["access.adb.consent"] shouldBe "false"
        incident.context["access.adb.lastKnown"] shouldBe "true"
    }

    @Test
    fun `a capability that was never probed reads as unknown`() = runTest {
        val incident = factory(rootConsent = flowOf(null)).freeze(IOException("boom"))

        incident.context["access.root.consent"] shouldBe "unknown"
        incident.context["access.adb.lastKnown"] shouldBe "unknown"
    }

    @Test
    fun `a settings read that throws does not take the freeze with it`() = runTest {
        val incident = factory(
            rootConsent = flow { throw IOException("corrupt preferences") },
        ).freeze(IOException("boom"))

        incident.context["access.root.consent"] shouldBe "unknown"
        // The other fields are still collected
        incident.context["access.adb.consent"] shouldBe "false"
    }

    @Test
    fun `cancellation is never swallowed`() = runTest {
        shouldThrow<CancellationException> {
            factory(rootConsent = flow { throw CancellationException("gone") }).freeze(IOException("boom"))
        }
    }

    @Test
    fun `the site's own context keys survive and nulls are dropped`() = runTest {
        val incident = factory().freeze(
            error = IOException("boom"),
            siteContext = mapOf("nav.target" to "Home", "nav.location" to null),
        )

        incident.context["nav.target"] shouldBe "Home"
        incident.context.containsKey("nav.location") shouldBe false
    }

    @Test
    fun `an explicit error time is not marked approximate`() = runTest {
        val exact = Instant.fromEpochMilliseconds(1_700_000_000_000)

        val stamped = factory().freeze(IOException("boom"), occurredAt = exact)
        stamped.occurredAt shouldBe exact
        stamped.occurredAtIsApproximate shouldBe false

        factory().freeze(IOException("boom")).occurredAtIsApproximate shouldBe true
    }
}
