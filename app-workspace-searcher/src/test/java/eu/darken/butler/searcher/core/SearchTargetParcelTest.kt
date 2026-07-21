package eu.darken.butler.searcher.core

import android.os.Parcel
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchTargetParcelTest : BaseTest() {

    private fun roundTrip(target: SearchTarget): SearchTarget {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(target, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            parcel.readParcelable(SearchTarget::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `every mediastore collection parcels round-trip`() {
        SearchTarget.MediaStore.Collection.entries.forEach { collection ->
            val original = SearchTarget.MediaStore(collection, enabled = false)
            roundTrip(original) shouldBe original
        }
    }

    @Test
    fun `path target still parcels round-trip`() {
        val original = SearchTarget.Path(path = LocalPath.build("/storage/emulated/0/Download"))
        roundTrip(original) shouldBe original
    }
}
