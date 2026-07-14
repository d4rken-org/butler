package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveDiskCacheTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cacheDir = File(context.cacheDir, "archives")
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setup() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    @After
    fun teardown() {
        appScope.cancel()
        cacheDir.deleteRecursively()
    }

    private fun create() = ArchiveDiskCache(
        context = context,
        appScope = appScope,
        dispatcherProvider = TestDispatcherProvider(),
    )

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test
    fun `startup sweep removes decrypted plaintext and partial files but keeps container caches`() {
        val staleDecrypted = File(cacheDir, "entrydec-stale").apply { writeText("secret") }
        val partial = File(cacheDir, "container-x.part").apply { writeText("half") }
        val cachedContainer = File(cacheDir, "container-keep").apply { writeText("cached") }

        // Construction eagerly runs the sweep.
        create()

        staleDecrypted.exists() shouldBe false
        partial.exists() shouldBe false
        // Whole-container caches are session-independent and must survive the sweep.
        cachedContainer.exists() shouldBe true
    }

    @Test
    fun `decrypted content left by a previous session is not served - the producer re-runs`() = runTest {
        val key = "entry-1"
        // A leftover decrypted file sitting at the exact cache path a materialize(key) would hit.
        File(cacheDir, "${ArchiveDiskCache.PREFIX_EPHEMERAL_DECRYPTED}-${sha256Hex(key)}")
            .writeText("STALE-PLAINTEXT")

        val cache = create()

        var producerRan = false
        val result = cache.materialize(ArchiveDiskCache.PREFIX_EPHEMERAL_DECRYPTED, key) { part ->
            producerRan = true
            part.writeText("FRESH-DECRYPTED")
        }

        // The barrier guarantees the stale file was swept before the fast path could return it, so the
        // producer (password-backed re-decryption) must have run.
        producerRan shouldBe true
        result.readText() shouldBe "FRESH-DECRYPTED"
    }

    @Test
    fun `materialize reuses a container cache produced within the same session`() = runTest {
        val cache = create()
        val key = "container-1"

        var productions = 0
        val first = cache.materialize("container", key) { part ->
            productions++
            part.writeText("DATA")
        }
        val second = cache.materialize("container", key) { part ->
            productions++
            part.writeText("DATA")
        }

        productions shouldBe 1
        first shouldBe second
        second.readText() shouldBe "DATA"
    }
}
