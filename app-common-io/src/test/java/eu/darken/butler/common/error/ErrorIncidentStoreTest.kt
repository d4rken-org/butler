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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        store.alias(wrapper, original)

        store.get(wrapper).shouldNotBeNull().incidentId shouldBe incident.incidentId
        store.getOrFreeze(wrapper).context.containsKey("incident.frozenAtShare") shouldBe false
        spooled().size shouldBe 1
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
