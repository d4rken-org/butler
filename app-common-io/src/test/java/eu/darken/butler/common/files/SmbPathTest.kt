package eu.darken.butler.common.files

import android.content.Context
import android.os.Parcel
import eu.darken.butler.common.files.extensions.crumbsTo
import eu.darken.butler.common.files.extensions.isAncestorOf
import eu.darken.butler.common.files.extensions.isParentOf
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.common.files.extensions.removePrefix
import eu.darken.butler.common.files.extensions.startsWith
import eu.darken.butler.common.files.network.NetworkLocationNames
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.PolymorphicSerializer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbPathTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val otherLocationId = Uuid.parse("99999999-8888-7777-6666-555555555555")

    private val context = mockk<Context>()

    private fun location(id: Uuid, label: String?, share: String) = SmbLocation(
        id = id,
        label = label,
        host = "nas.local",
        share = share,
        authType = SmbLocation.AuthType.GUEST,
        rememberCredential = false,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @After
    fun resetNames() = NetworkLocationNames.clear()

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

    @Test
    fun `a known location is shown by its name`() {
        NetworkLocationNames.updateSmb(listOf(location(locationId, label = "Home NAS", share = "photos")))

        val root = SmbPath.root(locationId)
        root.userReadablePath.get(context) shouldBe "smb://Home NAS"
        root.userReadableName.get(context) shouldBe "Home NAS"

        val nested = SmbPath(locationId, listOf("Photos", "a.jpg"))
        nested.userReadablePath.get(context) shouldBe "smb://Home NAS/Photos/a.jpg"
        nested.userReadableName.get(context) shouldBe "a.jpg"
    }

    @Test
    fun `without a label the share names the location`() {
        NetworkLocationNames.updateSmb(listOf(location(locationId, label = " ", share = "photos")))

        SmbPath(locationId, listOf("a.jpg")).userReadablePath.get(context) shouldBe "smb://photos/a.jpg"
    }

    @Test
    fun `an unknown location is shown by its id`() {
        NetworkLocationNames.updateSmb(listOf(location(otherLocationId, label = "Home NAS", share = "photos")))

        val root = SmbPath.root(locationId)
        root.userReadablePath.get(context) shouldBe "smb://$locationId"
        root.userReadableName.get(context) shouldBe locationId.toString()
        SmbPath(locationId, listOf("Photos", "a.jpg")).userReadablePath.get(context) shouldBe
            "smb://$locationId/Photos/a.jpg"
    }

    @Test
    fun `the path string keeps the id whether or not the name is known`() {
        val root = SmbPath.root(locationId)
        val nested = SmbPath(locationId, listOf("Photos", "a.jpg"))
        root.path shouldBe "smb://$locationId"
        nested.path shouldBe "smb://$locationId/Photos/a.jpg"

        NetworkLocationNames.updateSmb(listOf(location(locationId, label = "Home NAS", share = "photos")))

        root.path shouldBe "smb://$locationId"
        root.name shouldBe locationId.toString()
        nested.path shouldBe "smb://$locationId/Photos/a.jpg"
    }
}
