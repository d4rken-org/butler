package eu.darken.butler.common.files.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class LocalPathCopyDeviceTest {

    @get:Rule val storage = DeviceStorageRule()

    private val ops = LocalFileSystemOps(ownershipResolver = mockk<OwnershipResolver>(relaxed = true))

    @Test
    fun copyOntoSharedStoragePreservesModifiedTime() = runTest {
        val content = ByteArray(8 * 1024) { (it % 251).toByte() }
        val source = File(storage.privateRoot, "dated.txt").apply { writeBytes(content) }
        source.setLastModified(Instant.parse("2024-03-15T10:20:30Z").toEpochMilliseconds()) shouldBe true
        val sourceModified = source.lastModified()

        LocalPath.build(source)
            .copy(ops, LocalPath.build(storage.sharedRoot), options = CopyAction.Options(preserveAttributes = true))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        val copied = File(storage.sharedRoot, "dated.txt")
        copied.exists() shouldBe true
        copied.readBytes().contentEquals(content) shouldBe true
        copied.lastModified() / 1000 shouldBe sourceModified / 1000
    }
}
