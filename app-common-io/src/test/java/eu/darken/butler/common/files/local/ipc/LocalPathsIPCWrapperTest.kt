package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class LocalPathsIPCWrapperTest : BaseTest() {

    private fun roundTrip(paths: List<LocalPath>): List<LocalPath> {
        val parcel = Parcel.obtain()
        return try {
            LocalPathsIPCWrapper(paths).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            LocalPathsIPCWrapper.createFromParcel(parcel).payload
        } finally {
            parcel.recycle()
        }
    }

    /**
     * More than one element on purpose: an array of one only ever takes the cold creator lookup,
     * every further element reads through the process-static creator cache.
     */
    @Test
    fun `several paths survive the parcel`() {
        val paths = listOf(
            LocalPath.build("/storage/emulated/0/alpha"),
            LocalPath.build("/storage/emulated/0/beta"),
            LocalPath.build("/storage/emulated/0/nested", "gamma.txt"),
        )

        roundTrip(paths) shouldBe paths
    }

    @Test
    fun `an empty listing survives the parcel`() {
        roundTrip(emptyList()) shouldBe emptyList()
    }
}
