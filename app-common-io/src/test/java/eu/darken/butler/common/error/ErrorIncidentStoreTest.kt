package eu.darken.butler.common.error

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.debug.logging.RingLogBuffer
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class ErrorIncidentStoreTest : BaseTest() {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val spoolDir: File
        get() = File(File(app.cacheDir, ErrorReportPackager.REPORTS_DIR), "incidents")

    /** Value equality, which the store must not use: two of these are equal but different failures. */
    private data class ValueEqualException(override val message: String) : Exception(message)

    private fun store() = ErrorIncidentStore(
        ErrorIncidentFactory(
            context = app,
            ringLogBuffer = RingLogBuffer(),
            dispatcherProvider = TestDispatcherProvider(),
            rootSettings = mockk<RootSettings>().apply { every { useRoot } returns value() },
            adbSettings = mockk<AdbSettings>().apply { every { useShizuku } returns value() },
            rootManager = mockk<RootManager>().apply { every { lastKnownRooted } returns null },
            shizukuManager = mockk<ShizukuManager>().apply { every { lastShizukudResult } returns null },
        ),
    )

    private fun value() = mockk<DataStoreValue<Boolean?>>().apply { every { flow } returns flowOf(null) }

    /** A factory whose freeze can be held open, to observe what a freeze in flight blocks. */
    private fun gatedFactory(held: Throwable, gate: CompletableDeferred<Unit>) = mockk<ErrorIncidentFactory>().apply {
        var counter = 0
        coEvery { clearStaleSpools() } just Runs
        coEvery { freeze(any(), any(), any(), any()) } coAnswers {
            if (firstArg<Throwable>() === held) gate.await()
            ErrorIncident(
                incidentId = "incident-${counter++}",
                occurredAt = Clock.System.now(),
                occurredAtIsApproximate = false,
                error = firstArg(),
                context = emptyMap(),
                logFile = null,
            )
        }
    }

    private fun spooled(): List<File> = spoolDir.listFiles()?.toList() ?: emptyList()

    @Before
    fun setup() {
        spoolDir.deleteRecursively()
    }

    @Test
    fun `two equal but distinct failures are two incidents`() = runTest {
        val store = store()

        val first = store.remember(ValueEqualException("boom"))
        val second = store.remember(ValueEqualException("boom"))

        first.incidentId shouldNotBe second.incidentId
        spooled().size shouldBe 2
    }

    @Test
    fun `remembering the same failure twice changes nothing`() = runTest {
        val store = store()
        val boom = IOException("boom")

        val first = store.remember(boom, mapOf("nav.target" to "Home"))
        val second = store.remember(boom, mapOf("nav.target" to "Device"))

        second.incidentId shouldBe first.incidentId
        second.occurredAt shouldBe first.occurredAt
        second.context shouldBe first.context
        spooled().size shouldBe 1
    }

    @Test
    fun `racing callers mint one incident`() = runTest {
        val store = store()
        val boom = IOException("boom")

        val incidents = List(8) { async { store.remember(boom) } }.awaitAll()

        incidents.map { it.incidentId }.distinct().size shouldBe 1
        spooled().size shouldBe 1
    }

    @Test
    fun `a caller racing the end of a freeze mints one incident`() = runTest {
        val store = store()
        val boom = IOException("boom")
        val bothInside = CyclicBarrier(2)

        val incidents = withContext(Dispatchers.Default) {
            List(2) {
                async {
                    bothInside.await(5, TimeUnit.SECONDS)
                    store.remember(boom)
                }
            }.awaitAll()
        }

        incidents.map { it.incidentId }.distinct().size shouldBe 1
        store.get(boom)!!.incidentId shouldBe incidents.first().incidentId
        spooled().size shouldBe 1
    }

    @Test
    fun `an unrelated failure does not queue behind a freeze in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val blocked = IOException("blocked")
        val store = ErrorIncidentStore(gatedFactory(blocked, gate))

        val held = async { store.remember(blocked) }
        runCurrent()

        val other = store.remember(IOException("unrelated"))

        // The unrelated failure is frozen while the first freeze is still held open.
        held.isCompleted shouldBe false

        gate.complete(Unit)
        held.await().incidentId shouldNotBe other.incidentId
    }

    @Test
    fun `a freeze that fails does not wedge the next caller`() = runTest {
        val boom = IOException("boom")
        var attempts = 0
        val factory = mockk<ErrorIncidentFactory>().apply {
            coEvery { clearStaleSpools() } just Runs
            coEvery { freeze(any(), any(), any(), any()) } coAnswers {
                if (attempts++ == 0) throw IllegalStateException("spool broke")
                ErrorIncident(
                    incidentId = "incident-retry",
                    occurredAt = Clock.System.now(),
                    occurredAtIsApproximate = false,
                    error = firstArg(),
                    context = emptyMap(),
                    logFile = null,
                )
            }
        }
        val store = ErrorIncidentStore(factory)

        shouldThrow<IllegalStateException> { store.remember(boom) }

        store.remember(boom).incidentId shouldBe "incident-retry"
    }

    @Test
    fun `spool files from a previous process are dropped before the first freeze`() = runTest {
        val store = store()
        spoolDir.mkdirs()
        val stale = File(spoolDir, "stale.log").apply { writeText("previous process") }

        val incident = store.remember(IOException("boom"))

        stale.exists() shouldBe false
        incident.logFile!!.exists() shouldBe true
        spooled().size shouldBe 1
    }

    @Test
    fun `an evicted incident takes its log trail with it`() = runTest {
        val store = store()
        val eldest = IOException("eldest")

        val evicted = store.remember(eldest)
        repeat(ErrorIncidentStore.MAX_ENTRIES) { store.remember(IOException("boom $it")) }

        store.get(eldest) shouldBe null
        evicted.logFile!!.exists() shouldBe false
        spooled().size shouldBe ErrorIncidentStore.MAX_ENTRIES
    }

    @Test
    fun `a pinned incident keeps its place and its log trail`() = runTest {
        val store = store()
        val pinned = IOException("pinned")
        val unpinned = IOException("unpinned")

        val held = store.remember(pinned)
        store.pin(held)
        val evictable = store.remember(unpinned)
        repeat(ErrorIncidentStore.MAX_ENTRIES) { store.remember(IOException("boom $it")) }

        store.get(pinned)?.incidentId shouldBe held.incidentId
        held.logFile!!.exists() shouldBe true
        // The eldest unpinned entry was evicted in its place
        store.get(unpinned) shouldBe null
        evictable.logFile!!.exists() shouldBe false
    }

    @Test
    fun `unpinning hands the incident back to eviction`() = runTest {
        val store = store()
        val eldest = IOException("eldest")

        val incident = store.remember(eldest)
        store.pin(incident)
        store.unpin(incident)
        repeat(ErrorIncidentStore.MAX_ENTRIES) { store.remember(IOException("boom $it")) }

        store.get(eldest) shouldBe null
        incident.logFile!!.exists() shouldBe false
    }

    @Test
    fun `an incident two shares hold survives the first release`() = runTest {
        val store = store()
        val shared = IOException("shared")

        val incident = store.remember(shared)
        // A confirmed share is still packaging while the same error is offered for sharing again
        store.pin(incident)
        store.pin(incident)
        store.unpin(incident)
        repeat(ErrorIncidentStore.MAX_ENTRIES) { store.remember(IOException("boom $it")) }

        store.get(shared)?.incidentId shouldBe incident.incidentId
        incident.logFile!!.exists() shouldBe true

        store.unpin(incident)
        store.remember(IOException("one more"))

        store.get(shared) shouldBe null
        incident.logFile!!.exists() shouldBe false
    }

    @Test
    fun `an incident frozen at share time says so`() = runTest {
        val store = store()
        val boom = IOException("boom")

        store.getOrFreeze(boom).context["incident.frozenAtShare"] shouldBe "true"
        store.remember(boom).context["incident.frozenAtShare"] shouldBe "true"
    }

    @Test
    fun `an incident that was remembered is not marked as frozen at share time`() = runTest {
        val store = store()
        val boom = IOException("boom")

        val remembered = store.remember(boom)
        val shared = store.getOrFreeze(boom)

        shared.incidentId shouldBe remembered.incidentId
        shared.context.containsKey("incident.frozenAtShare") shouldBe false
    }

    @Test
    fun `a wrapper resolves to the incident of what it wraps`() = runTest {
        val store = store()
        val original = IOException("boom")
        val wrapper = IllegalStateException("wrapped", original)

        val incident = store.remember(original)
        store.alias(wrapper, original) shouldBe true

        store.get(wrapper).shouldNotBeNull().incidentId shouldBe incident.incidentId
        store.getOrFreeze(wrapper).context.containsKey("incident.frozenAtShare") shouldBe false
        spooled().size shouldBe 1
    }

    @Test
    fun `aliasing an original that is no longer stored installs nothing and says so`() = runTest {
        val store = store()
        val original = IOException("boom")
        val wrapper = IllegalStateException("wrapped", original)

        store.remember(original)
        repeat(ErrorIncidentStore.MAX_ENTRIES) { store.remember(IOException("boom $it")) }
        store.get(original) shouldBe null

        store.alias(wrapper, original) shouldBe false

        store.get(wrapper) shouldBe null
    }

    @Test
    fun `an incident two throwables name survives the eviction of the first one`() = runTest {
        val store = store()
        val original = IOException("boom")
        val wrapper = IllegalStateException("wrapped", original)

        val incident = store.remember(original)
        store.alias(wrapper, original)

        // One entry over the cap: the eldest key goes, which is the one the incident was minted for
        repeat(ErrorIncidentStore.MAX_ENTRIES - 1) { store.remember(IOException("boom $it")) }

        store.get(original) shouldBe null
        store.get(wrapper).shouldNotBeNull().incidentId shouldBe incident.incidentId
        incident.logFile!!.exists() shouldBe true

        // With the second key gone too, nothing can reach the log trail anymore
        store.remember(IOException("one more"))

        store.get(wrapper) shouldBe null
        incident.logFile!!.exists() shouldBe false
    }

    @Test
    fun `a forgotten incident releases its log trail`() = runTest {
        val store = store()
        val boom = IOException("boom")

        val incident = store.remember(boom)
        store.forget(boom)

        store.get(boom) shouldBe null
        incident.logFile!!.exists() shouldBe false
    }
}
