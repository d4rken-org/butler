package eu.darken.butler.saver.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SaveOperationTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var saveOperation: SaveOperation

    @Before
    fun setup() {
        context = mockk()
        contentResolver = mockk()
        gatewaySwitch = mockk()
        every { context.contentResolver } returns contentResolver
        saveOperation = SaveOperation(context, gatewaySwitch)
    }

    @Test
    fun `execute emits Success when copy completes successfully`() = runTest {
        val sourceUri = Uri.parse("content://com.example/file")
        val targetDir = LocalPath.build("/sdcard/Download")
        val filename = "test.txt"
        val content = "Hello, World!".toByteArray()
        val outputStream = ByteArrayOutputStream()

        every { contentResolver.openInputStream(sourceUri) } returns ByteArrayInputStream(content)
        coEvery { gatewaySwitch.exists(any<APath<*>>()) } returns false
        coEvery { gatewaySwitch.createFile(any<APath<*>>(), createParents = false) } returns Unit
        coEvery { gatewaySwitch.openOutputStream(any<APath<*>>(), append = false) } returns outputStream

        val states = saveOperation.execute(sourceUri, targetDir, filename, content.size.toLong()).toList()

        states.first().shouldBeInstanceOf<SaveOperation.State.Saving>()
        val success = states.last()
        success.shouldBeInstanceOf<SaveOperation.State.Success>()
        success.bytesWritten shouldBe content.size.toLong()
        outputStream.toByteArray() shouldBe content
    }

    @Test
    fun `execute emits SourceExpired when input stream is null`() = runTest {
        val sourceUri = Uri.parse("content://com.example/file")
        val targetDir = LocalPath.build("/sdcard/Download")
        val filename = "test.txt"

        every { contentResolver.openInputStream(sourceUri) } returns null

        val states = saveOperation.execute(sourceUri, targetDir, filename).toList()

        val lastState = states.last()
        lastState.shouldBeInstanceOf<SaveOperation.State.Error>()
        lastState.error shouldBe SaveOperation.SaveError.SourceExpired
    }

    @Test
    fun `execute emits SourceExpired on SecurityException`() = runTest {
        val sourceUri = Uri.parse("content://com.example/file")
        val targetDir = LocalPath.build("/sdcard/Download")
        val filename = "test.txt"

        every { contentResolver.openInputStream(sourceUri) } throws SecurityException("Permission revoked")

        val states = saveOperation.execute(sourceUri, targetDir, filename).toList()

        val lastState = states.last()
        lastState.shouldBeInstanceOf<SaveOperation.State.Error>()
        lastState.error shouldBe SaveOperation.SaveError.SourceExpired
    }

    @Test
    fun `execute emits FileExists when target file already exists`() = runTest {
        val sourceUri = Uri.parse("content://com.example/file")
        val targetDir = LocalPath.build("/sdcard/Download")
        val filename = "existing.txt"
        val content = "data".toByteArray()

        every { contentResolver.openInputStream(sourceUri) } returns ByteArrayInputStream(content)
        coEvery { gatewaySwitch.exists(any<APath<*>>()) } returns true

        val states = saveOperation.execute(sourceUri, targetDir, filename).toList()

        val lastState = states.last()
        lastState.shouldBeInstanceOf<SaveOperation.State.Error>()
        lastState.error.shouldBeInstanceOf<SaveOperation.SaveError.FileExists>()
    }

    @Test
    fun `execute emits WriteError on IOException during write`() = runTest {
        val sourceUri = Uri.parse("content://com.example/file")
        val targetDir = LocalPath.build("/sdcard/Download")
        val filename = "test.txt"
        val content = "Hello".toByteArray()

        every { contentResolver.openInputStream(sourceUri) } returns ByteArrayInputStream(content)
        coEvery { gatewaySwitch.exists(any<APath<*>>()) } returns false
        coEvery { gatewaySwitch.createFile(any<APath<*>>(), createParents = false) } returns Unit
        coEvery { gatewaySwitch.openOutputStream(any<APath<*>>(), append = false) } throws IOException("Disk full")

        val states = saveOperation.execute(sourceUri, targetDir, filename).toList()

        val lastState = states.last()
        lastState.shouldBeInstanceOf<SaveOperation.State.Error>()
        val error = lastState.error
        error.shouldBeInstanceOf<SaveOperation.SaveError.WriteError>()
        error.message shouldBe "Disk full"
    }

    @Test
    fun `execute calculates progress correctly with known totalBytes`() = runTest {
        val sourceUri = Uri.parse("content://com.example/file")
        val targetDir = LocalPath.build("/sdcard/Download")
        val filename = "test.bin"
        // Create data larger than the progress interval (64KB) to trigger progress updates
        val content = ByteArray(65536 * 2) { it.toByte() }
        val outputStream = ByteArrayOutputStream()

        every { contentResolver.openInputStream(sourceUri) } returns ByteArrayInputStream(content)
        coEvery { gatewaySwitch.exists(any<APath<*>>()) } returns false
        coEvery { gatewaySwitch.createFile(any<APath<*>>(), createParents = false) } returns Unit
        coEvery { gatewaySwitch.openOutputStream(any<APath<*>>(), append = false) } returns outputStream

        val states = saveOperation.execute(sourceUri, targetDir, filename, content.size.toLong()).toList()

        // Should have at least: initial Saving(0), one or more progress updates, and Success
        val savingStates = states.filterIsInstance<SaveOperation.State.Saving>()
        savingStates.isNotEmpty() shouldBe true

        // First saving state should start at 0
        savingStates.first().bytesWritten shouldBe 0L
        savingStates.first().totalBytes shouldBe content.size.toLong()

        // Progress should be calculable
        savingStates.first().progress shouldBe 0f

        // Final state should be success
        states.last().shouldBeInstanceOf<SaveOperation.State.Success>()
    }

    @Test
    fun `execute handles null totalBytes with indeterminate progress`() = runTest {
        val sourceUri = Uri.parse("content://com.example/file")
        val targetDir = LocalPath.build("/sdcard/Download")
        val filename = "test.txt"
        val content = "Small file".toByteArray()
        val outputStream = ByteArrayOutputStream()

        every { contentResolver.openInputStream(sourceUri) } returns ByteArrayInputStream(content)
        coEvery { gatewaySwitch.exists(any<APath<*>>()) } returns false
        coEvery { gatewaySwitch.createFile(any<APath<*>>(), createParents = false) } returns Unit
        coEvery { gatewaySwitch.openOutputStream(any<APath<*>>(), append = false) } returns outputStream

        val states = saveOperation.execute(sourceUri, targetDir, filename, totalBytes = null).toList()

        val savingState = states.first()
        savingState.shouldBeInstanceOf<SaveOperation.State.Saving>()
        savingState.totalBytes shouldBe null
        savingState.progress shouldBe null
    }
}
