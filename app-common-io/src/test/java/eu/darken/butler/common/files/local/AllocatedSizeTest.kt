package eu.darken.butler.common.files.local

import android.os.Parcel
import android.system.Os
import android.system.StructStat
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AllocatedSizeTest : BaseTest() {
    @Test fun `allocation is independent of apparent size and opt in`() = runTest {
        val file = File.createTempFile("allocation", ".bin").apply { writeBytes(byteArrayOf(1)) }
        mockkStatic(Os::class)
        try {
            every { Os.lstat(file.path) } returns StructStat(1, 2, 0, 1, 0, 0, 0, 1, 0, 0, 0, 4096, 8)
            val ops = LocalFileSystemOps(mockk<OwnershipResolver>())
            val path = LocalPath.build(file)
            ops.lookup(path, LookupOptions(fetchSize = true)).allocatedSize shouldBe null
            ops.lookup(path, LookupOptions(fetchSize = true, fetchAllocatedSize = true)).apply {
                size shouldBe 1L
                allocatedSize shouldBe 4096L
            }
            every { Os.lstat(file.path) } throws IllegalStateException("unavailable")
            ops.lookup(path, LookupOptions(fetchSize = true, fetchAllocatedSize = true)).apply {
                size shouldBe 1L
                allocatedSize shouldBe null
                error shouldBe null
            }
        } finally {
            unmockkStatic(Os::class)
            file.delete()
        }
    }

    @Test fun `the allocation lookup option crosses IPC`() {
        val options = LookupOptions(fetchSize = true, fetchAllocatedSize = true)
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(options, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            val restored = parcel.readParcelable<LookupOptions>(LookupOptions::class.java.classLoader)
            restored shouldBe options
        } finally {
            parcel.recycle()
        }
    }
}
