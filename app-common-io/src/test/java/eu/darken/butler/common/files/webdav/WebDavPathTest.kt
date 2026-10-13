package eu.darken.butler.common.files.webdav

import android.content.Context
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.extensions.commonParent
import eu.darken.butler.common.files.extensions.crumbsTo
import eu.darken.butler.common.files.extensions.isAncestorOf
import eu.darken.butler.common.files.extensions.isParentOf
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.common.files.extensions.removePrefix
import eu.darken.butler.common.files.extensions.shownPath
import eu.darken.butler.common.files.extensions.startsWith
import eu.darken.butler.common.files.extensions.toFile
import eu.darken.butler.common.files.network.NetworkLocationNames
import eu.darken.butler.common.files.sftp.testSftpLocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class WebDavPathTest : BaseTest() {

    private val locationA = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val locationB = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private val context = mockk<Context>()

    @AfterEach
    fun resetNames() = NetworkLocationNames.clear()

    @Test
    fun `the path string names the location and the segments`() {
        WebDavPath.root(locationA).path shouldBe "webdav://11111111-1111-1111-1111-111111111111"
        WebDavPath(locationA, listOf("photos", "a.jpg")).path shouldBe
            "webdav://11111111-1111-1111-1111-111111111111/photos/a.jpg"
    }

    @Test
    fun `the root is named after its location and has no parent`() {
        val root = WebDavPath.root(locationA)
        root.name shouldBe locationA.toString()
        root.parent shouldBe null
        root.child("a", "b").parent shouldBe WebDavPath(locationA, listOf("a"))
        root.child("a", "b").name shouldBe "b"
    }

    @Test
    fun `names that need URL encoding are ordinary segments`() {
        val names = listOf("a b", "100%", "#1", "?q=1", "a\\b", "c:d", "trailing.", " ", "Überweisung")
        WebDavPath(locationA, names).segments shouldBe names
    }

    @Test
    fun `only the separator, NUL and the traversal names reject a segment`() {
        listOf("", ".", "..", "a/b", "a\u0000b").forEach { segment ->
            shouldThrow<IllegalArgumentException> { WebDavPath(locationA, listOf(segment)) }
        }
        shouldThrow<IllegalArgumentException> { WebDavPath.root(locationA).child("..") }
    }

    @Test
    fun `relations hold within one location`() {
        val root = WebDavPath.root(locationA)
        val photos = WebDavPath(locationA, listOf("photos"))
        val photo = photos.child("2026", "a.jpg")

        root.isAncestorOf(photo) shouldBe true
        photos.isParentOf(photos.child("2026")) shouldBe true
        photos.isParentOf(photo) shouldBe false
        photo.startsWith(WebDavPath(locationA, listOf("pho"))) shouldBe true
        photo.removePrefix(photos) shouldBe listOf("2026", "a.jpg")
        photos.crumbsTo(photo).toList() shouldBe listOf("2026", "a.jpg")
        photo.matches(WebDavPath(locationA, listOf("photos", "2026", "a.jpg"))) shouldBe true
    }

    @Test
    fun `paths under different locations are unrelated`() {
        val a = WebDavPath(locationA, listOf("photos"))
        val b = WebDavPath(locationB, listOf("photos"))

        a.matches(b) shouldBe false
        WebDavPath.root(locationA).isAncestorOf(b) shouldBe false
        a.isParentOf(b.child("1")) shouldBe false
        b.startsWith(WebDavPath.root(locationA)) shouldBe false
        shouldThrow<IllegalArgumentException> { WebDavPath.root(locationA).crumbsTo(b) }
        shouldThrow<IllegalArgumentException> { b.removePrefix(WebDavPath.root(locationA)) }
        listOf(a.child("1"), b.child("2")).commonParent() shouldBe null
        listOf(a.child("1"), a.child("2")).commonParent() shouldBe a
    }

    @Test
    fun `a WebDAV path has no File representation`() {
        shouldThrow<IllegalArgumentException> { WebDavPath(locationA, listOf("a.txt")).toFile() }
    }

    @Test
    fun `a known location is shown by its name`() {
        NetworkLocationNames.updateWebDav(listOf(testWebDavLocation(locationA)))

        val root = WebDavPath.root(locationA)
        root.userReadablePath.get(context) shouldBe "webdav://cloud.example.com"
        root.userReadableName.get(context) shouldBe "cloud.example.com"

        val nested = WebDavPath(locationA, listOf("usr", "bin"))
        nested.userReadablePath.get(context) shouldBe "webdav://cloud.example.com/usr/bin"
        nested.userReadableName.get(context) shouldBe "bin"
        nested.shownPath.get(context) shouldBe "webdav://cloud.example.com/usr/bin"
    }

    @Test
    fun `a label wins over the host`() {
        NetworkLocationNames.updateWebDav(listOf(testWebDavLocation(locationA).copy(label = "Nextcloud")))

        WebDavPath(locationA, listOf("usr", "bin")).userReadablePath.get(context) shouldBe "webdav://Nextcloud/usr/bin"
    }

    @Test
    fun `an unknown location is shown by its id`() {
        NetworkLocationNames.updateWebDav(listOf(testWebDavLocation(locationB)))

        val root = WebDavPath.root(locationA)
        root.userReadablePath.get(context) shouldBe "webdav://11111111-1111-1111-1111-111111111111"
        root.userReadableName.get(context) shouldBe "11111111-1111-1111-1111-111111111111"
        WebDavPath(locationA, listOf("usr", "bin")).userReadablePath.get(context) shouldBe
            "webdav://11111111-1111-1111-1111-111111111111/usr/bin"
    }

    @Test
    fun `names of other protocols never leak into a WebDAV path`() {
        NetworkLocationNames.updateSftp(listOf(testSftpLocation(locationA, host = "cnc-dev")))

        WebDavPath.root(locationA).userReadablePath.get(context) shouldBe
            "webdav://11111111-1111-1111-1111-111111111111"
    }

    @Test
    fun `the path string keeps the id whether or not the name is known`() {
        val root = WebDavPath.root(locationA)
        val nested = WebDavPath(locationA, listOf("usr", "bin"))
        root.path shouldBe "webdav://11111111-1111-1111-1111-111111111111"
        nested.path shouldBe "webdav://11111111-1111-1111-1111-111111111111/usr/bin"

        NetworkLocationNames.updateWebDav(listOf(testWebDavLocation(locationA)))

        root.path shouldBe "webdav://11111111-1111-1111-1111-111111111111"
        root.name shouldBe "11111111-1111-1111-1111-111111111111"
        nested.path shouldBe "webdav://11111111-1111-1111-1111-111111111111/usr/bin"
    }
}
