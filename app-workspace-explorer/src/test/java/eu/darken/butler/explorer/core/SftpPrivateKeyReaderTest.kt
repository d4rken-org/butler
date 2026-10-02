package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.ByteArrayInputStream
import java.io.IOException

class SftpPrivateKeyReaderTest : BaseTest() {

    private val path = LocalPath.build("/storage/emulated/0/keys/id_ed25519")

    private val gatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { useRes<Any?>(any()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(this@apply)
        }
    }

    private fun file(type: FileType = FileType.FILE, content: ByteArray = ByteArray(0)) {
        val lookup = mockk<APathLookup<APath<*>>> { every { fileType } returns type }
        coEvery { gatewaySwitch.lookup(path, any<LookupOptions>()) } returns lookup
        coEvery { gatewaySwitch.openInputStream(path) } answers { ByteArrayInputStream(content) }
    }

    private val reader = SftpPrivateKeyReader(gatewaySwitch)

    @Test
    fun `a key file is read with its name`() = runTest {
        file(content = "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray())

        val loaded = reader.read(path).shouldBeInstanceOf<SftpPrivateKeyReader.Result.Loaded>()

        loaded.name shouldBe "id_ed25519"
        loaded.bytes.decodeToString() shouldBe "-----BEGIN OPENSSH PRIVATE KEY-----"
    }

    @Test
    fun `a file of exactly the cap is still read`() = runTest {
        file(content = ByteArray(SftpPrivateKeyReader.MAX_BYTES) { 1 })

        reader.read(path).shouldBeInstanceOf<SftpPrivateKeyReader.Result.Loaded>().bytes.size shouldBe
            SftpPrivateKeyReader.MAX_BYTES
    }

    @Test
    fun `a file past the cap is rejected, whatever size it reports`() = runTest {
        file(content = ByteArray(SftpPrivateKeyReader.MAX_BYTES + 1) { 1 })

        reader.read(path) shouldBe SftpPrivateKeyReader.Result.TooLarge
    }

    @Test
    fun `a folder is not a key file`() = runTest {
        file(type = FileType.DIRECTORY)

        reader.read(path) shouldBe SftpPrivateKeyReader.Result.NotAFile
    }

    @Test
    fun `a failing read is reported, not thrown`() = runTest {
        file()
        val failure = IOException("gone")
        coEvery { gatewaySwitch.openInputStream(path) } throws failure

        reader.read(path) shouldBe SftpPrivateKeyReader.Result.Failed(failure)
    }
}
