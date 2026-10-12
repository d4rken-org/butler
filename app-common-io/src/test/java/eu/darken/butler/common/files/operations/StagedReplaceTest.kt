package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File

class StagedReplaceTest : BaseTest() {

    private val ops = LocalFileSystemOps(mockk<OwnershipResolver>(relaxed = true))

    @Test
    fun `a destination name near the filesystem limit can still be staged`(@TempDir tempDir: File) = runTest {
        val name = "厨".repeat(39) + "_com.example.app_" + "房".repeat(32) + "_123456789.apks"
        name.toByteArray(Charsets.UTF_8).size shouldBe 245
        val destination = LocalPath.build(tempDir).child(name)
        val payload = "split apks".toByteArray()

        val staging = StagedReplace.stagingPathFor(destination, ops)
        ops.openOutputStream(staging).use { it.write(payload) }
        StagedReplace.commit(staging, destination, ops)

        File(tempDir, name).readBytes() shouldBe payload
        staging.name.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual 255
        staging.name shouldEndWith ".apks"
    }

    @Test
    fun `an existing destination near the filesystem limit can be replaced`(@TempDir tempDir: File) = runTest {
        val name = "厨".repeat(39) + "_com.example.app_" + "房".repeat(32) + "_123456789.apks"
        File(tempDir, name).writeBytes("old".toByteArray())
        val destination = LocalPath.build(tempDir).child(name)
        val payload = "new".toByteArray()

        val staging = StagedReplace.stagingPathFor(destination, ops)
        ops.openOutputStream(staging).use { it.write(payload) }
        StagedReplace.commit(staging, destination, ops)

        File(tempDir, name).readBytes() shouldBe payload
        tempDir.list()!!.toList() shouldBe listOf(name)
    }

    @Test
    fun `a short destination name is staged unchanged`(@TempDir tempDir: File) = runTest {
        val destination = LocalPath.build(tempDir).child("photo.jpg")

        val staging = StagedReplace.stagingPathFor(destination, ops)

        staging.name shouldMatch Regex("""\.[0-9a-f]{8}\.part\.photo\.jpg""")
    }
}
