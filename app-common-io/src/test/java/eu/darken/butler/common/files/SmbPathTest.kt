package eu.darken.butler.common.files

import android.os.Parcel
import eu.darken.butler.common.files.extensions.crumbsTo
import eu.darken.butler.common.files.extensions.isAncestorOf
import eu.darken.butler.common.files.extensions.isParentOf
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.common.files.extensions.removePrefix
import eu.darken.butler.common.files.extensions.startsWith
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.PolymorphicSerializer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbPathTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val otherLocationId = Uuid.parse("99999999-8888-7777-6666-555555555555")

    @Test
    fun `root path has no segments`() {
        val root = SmbPath.root(locationId)
        root.segments shouldBe emptyList()
        root.parent shouldBe null
        root.name shouldBe locationId.toString()
        root.path shouldBe "smb://$locationId"
    }

    @Test
    fun `child appends segments`() {
        val child = SmbPath.root(locationId).child("movies", "2024.mkv")
        child.segments shouldBe listOf("movies", "2024.mkv")
        child.name shouldBe "2024.mkv"
        child.path shouldBe "smb://$locationId/movies/2024.mkv"
    }

    @Test
    fun `parent walks up one segment`() {
        val child = SmbPath(locationId, listOf("a", "b", "c"))
        child.parent shouldBe SmbPath(locationId, listOf("a", "b"))
        child.parent?.parent shouldBe SmbPath(locationId, listOf("a"))
    }

    @Test
    fun `structurally unusable segments are rejected at construction`() {
        shouldThrow<IllegalArgumentException> { SmbPath(locationId, listOf("..")) }
        shouldThrow<IllegalArgumentException> { SmbPath(locationId, listOf(".")) }
        shouldThrow<IllegalArgumentException> { SmbPath(locationId, listOf("a", "")) }
        shouldThrow<IllegalArgumentException> { SmbPath(locationId, listOf("a/b")) }
        shouldThrow<IllegalArgumentException> { SmbPath(locationId, listOf("a\\b")) }
    }

    @Test
    fun `names a server accepted stay constructible`() {
        SmbPath(locationId, listOf("weird:name*", "with?chars")).segments shouldBe
            listOf("weird:name*", "with?chars")
    }

    @Test
    fun `relations require the same location`() {
        val parent = SmbPath(locationId, listOf("a"))
        val child = SmbPath(locationId, listOf("a", "b"))
        val foreign = SmbPath(otherLocationId, listOf("a", "b"))

        parent.isParentOf(child) shouldBe true
        parent.isAncestorOf(child) shouldBe true
        child.startsWith(parent) shouldBe true
        child.matches(child) shouldBe true

        parent.isParentOf(foreign) shouldBe false
        parent.isAncestorOf(foreign) shouldBe false
        foreign.startsWith(parent) shouldBe false
        child.matches(foreign) shouldBe false
    }

    @Test
    fun `crumbsTo and removePrefix yield the relative segments`() {
        val parent = SmbPath(locationId, listOf("a"))
        val child = SmbPath(locationId, listOf("a", "b", "c"))

        parent.crumbsTo(child).toList() shouldBe listOf("b", "c")
        child.removePrefix(parent) shouldBe listOf("b", "c")
    }

    @Test
    fun `crumbsTo rejects a different location`() {
        val parent = SmbPath(locationId, listOf("a"))
        val foreign = SmbPath(otherLocationId, listOf("a", "b"))
        shouldThrow<IllegalArgumentException> { parent.crumbsTo(foreign) }
    }

    @Test
    fun `polymorphic json round trip`() {
        val json = SerializationIOModule().json()
        val original: APath<*> = SmbPath(locationId, listOf("movies", "2024.mkv"))

        val encoded = json.encodeToString(PolymorphicSerializer(APath::class), original)
        val restored = json.decodeFromString(PolymorphicSerializer(APath::class), encoded)

        restored shouldBe original
    }

    @Test
    fun `parcel round trip`() {
        val original = SmbPath(locationId, listOf("movies", "2024.mkv"))

        val parcel = Parcel.obtain()
        parcel.writeParcelable(original, 0)
        parcel.setDataPosition(0)
        @Suppress("DEPRECATION")
        val restored = parcel.readParcelable<SmbPath>(SmbPath::class.java.classLoader)
        parcel.recycle()

        restored shouldBe original
    }
}
