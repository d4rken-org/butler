package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class LocalPathLookupsIPCWrapperTest : BaseTest() {

    private fun roundTrip(lookups: List<LocalPathLookup>): List<LocalPathLookup> {
        val parcel = Parcel.obtain()
        return try {
            LocalPathLookupsIPCWrapper(lookups).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            LocalPathLookupsIPCWrapper.createFromParcel(parcel).payload
        } finally {
            parcel.recycle()
        }
    }

    /**
     * More than one element on purpose: an array of one only ever takes the cold creator lookup,
     * every further element reads through the process-static creator cache.
     */
    @Test
    fun `several lookups survive the parcel`() {
        val lookups = listOf(
            LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/alpha"),
                fileType = FileType.DIRECTORY,
                size = 4096L,
                modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            ),
            LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/beta.txt"),
                fileType = FileType.FILE,
                size = 512L,
                modifiedAt = null,
            ),
            LocalPathLookup.unknown(
                path = LocalPath.build("/storage/emulated/0/gamma"),
                error = "listing denied",
            ),
        )

        roundTrip(lookups) shouldBe lookups
    }

    @Test
    fun `an empty listing survives the parcel`() {
        roundTrip(emptyList()) shouldBe emptyList()
    }
}
