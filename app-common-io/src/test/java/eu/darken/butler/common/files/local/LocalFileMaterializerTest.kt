package eu.darken.butler.common.files.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class LocalFileMaterializerTest : BaseTest() {

    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val context = mockk<Context>()
    private val dispatcherProvider = TestDispatcherProvider()

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3A"
    private val safPath = SAFPath.build(treeUri, "app", "test.apk")
    private val content = "PK-fake-apk-bytes".toByteArray()

    private lateinit var cacheRoot: File
    private val materializedDir get() = File(cacheRoot, "materialized")

    @Before
    fun setup() {
        cacheRoot = File.createTempFile("mat_test", "").apply {
            delete()
            mkdirs()
        }
        every { context.cacheDir } returns cacheRoot
    }

    @After
    fun teardown() {
        cacheRoot.deleteRecursively()
    }

    private fun create() = LocalFileMaterializer(context, dispatcherProvider, gatewaySwitch)

    @Test
    fun `local path is handed over without copying`() = runTest2 {
        val localFile = File(cacheRoot, "real.apk").apply { writeBytes(content) }
        val localPath = LocalPath.build(localFile)

        var captured: File? = null
        create().useLocalFile(localPath) { file ->
            captured = file
            file.readBytes().toList() shouldBe content.toList()
        }

        captured shouldBe localFile
        coVerify(exactly = 0) { gatewaySwitch.openInputStream(any()) }
        materializedDir.exists() shouldBe false
    }

    @Test
    fun `non-local path is copied to a temp file then deleted`() = runTest2 {
        coEvery { gatewaySwitch.openInputStream(safPath) } answers { ByteArrayInputStream(content) }

        var captured: File? = null
        val result = create().useLocalFile(safPath) { file ->
            captured = file
            file.exists() shouldBe true
            file.name.endsWith(".apk") shouldBe true
            file.readBytes().toList() shouldBe content.toList()
            "done"
        }

        result shouldBe "done"
        captured!!.exists() shouldBe false
        materializedDir.listFiles()?.toList().orEmpty() shouldBe emptyList()
        coVerify(exactly = 1) { gatewaySwitch.openInputStream(safPath) }
    }

    @Test
    fun `temp file is deleted when the block throws`() = runTest2 {
        coEvery { gatewaySwitch.openInputStream(safPath) } answers { ByteArrayInputStream(content) }

        var captured: File? = null
        shouldThrow<IllegalStateException> {
            create().useLocalFile(safPath) { file ->
                captured = file
                throw IllegalStateException("boom")
            }
        }

        captured!!.exists() shouldBe false
    }

    @Test
    fun `temp file is deleted and cancellation propagates`() = runTest2 {
        coEvery { gatewaySwitch.openInputStream(safPath) } answers { ByteArrayInputStream(content) }

        var captured: File? = null
        shouldThrow<CancellationException> {
            create().useLocalFile(safPath) { file ->
                captured = file
                throw CancellationException("cancelled")
            }
        }

        captured!!.exists() shouldBe false
    }

    @Test
    fun `temp file is cleaned up when opening the stream fails`() = runTest2 {
        coEvery { gatewaySwitch.openInputStream(safPath) } throws java.io.IOException("read failed")

        shouldThrow<java.io.IOException> {
            create().useLocalFile(safPath) { "unreachable" }
        }

        // Temp was created before the copy ran; the finally must still remove it.
        materializedDir.listFiles()?.toList().orEmpty() shouldBe emptyList()
    }

    @Test
    fun `temp file is cleaned up when the stream fails mid-copy`() = runTest2 {
        val failingStream = object : java.io.InputStream() {
            private var calls = 0
            override fun read(): Int = throw java.io.IOException("single-byte read not used")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                calls++
                if (calls == 1) {
                    b[off] = 7
                    return 1
                }
                throw java.io.IOException("mid-copy failure")
            }
        }
        coEvery { gatewaySwitch.openInputStream(safPath) } answers { failingStream }

        shouldThrow<java.io.IOException> {
            create().useLocalFile(safPath) { "unreachable" }
        }

        materializedDir.listFiles()?.toList().orEmpty() shouldBe emptyList()
    }

    @Test
    fun `zero-byte file is materialized and cleaned up`() = runTest2 {
        coEvery { gatewaySwitch.openInputStream(safPath) } answers { ByteArrayInputStream(ByteArray(0)) }

        var captured: File? = null
        create().useLocalFile(safPath) { file ->
            captured = file
            file.exists() shouldBe true
            file.length() shouldBe 0L
        }

        captured!!.exists() shouldBe false
    }

    @Test
    fun `stale temp files from a previous run are swept on first use`() = runTest2 {
        materializedDir.mkdirs()
        val stale = File(materializedDir, "mat_stale12345.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        coEvery { gatewaySwitch.openInputStream(safPath) } answers { ByteArrayInputStream(content) }

        create().useLocalFile(safPath) { "ok" }

        stale.exists() shouldBe false
    }
}
