package eu.darken.butler.common.parcel

import android.os.Parcelable
import io.kotest.matchers.shouldBe
import kotlinx.parcelize.Parcelize
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParcelableExtensionsTest : BaseTest() {

    @Parcelize
    data class Payload(val name: String, val count: Int, val nested: Payload? = null) : Parcelable

    @Test
    fun `marshall and unmarshall round-trip`() {
        val payload = Payload("alpha", 42, Payload("beta", 7))

        payload.marshall().unmarshall<Payload>() shouldBe payload
    }

    /**
     * Twice in the same process on purpose: the first read populates the process-static creator
     * cache, the second one reads through it, and that cached branch is the one whose behaviour
     * differs between the typed and the untyped overload.
     */
    @Test
    fun `forceParcel round-trips repeatedly in one process`() {
        val first = Payload("alpha", 1)
        val second = Payload("beta", 2, Payload("gamma", 3))

        first.forceParcel() shouldBe first
        second.forceParcel() shouldBe second
    }
}
