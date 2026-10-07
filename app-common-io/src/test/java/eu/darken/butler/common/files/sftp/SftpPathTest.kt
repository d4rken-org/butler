package eu.darken.butler.common.files.sftp

import android.content.Context
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.extensions.commonParent
import eu.darken.butler.common.files.extensions.isAncestorOf
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.common.files.network.NetworkLocationNames
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class SftpPathTest : BaseTest() {

    private val locationA = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val locationB = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private val context = mockk<Context>()

    @AfterEach
    fun resetNames() = NetworkLocationNames.clear()

    @Test
    fun `the path string names the location and the segments`() {
        SftpPath.root(locationA).path shouldBe "sftp://11111111-1111-1111-1111-111111111111"
        SftpPath(locationA, listOf("photos", "a.jpg")).path shouldBe
            "sftp://11111111-1111-1111-1111-111111111111/photos/a.jpg"
    }

    @Test
    fun `the root is named after its location and has no parent`() {
        val root = SftpPath.root(locationA)
        root.name shouldBe locationA.toString()
        root.parent shouldBe null
        root.child("a", "b").parent shouldBe SftpPath(locationA, listOf("a"))
    }

    @Test
    fun `names windows would refuse are ordinary segments`() {
        val names = listOf("a\\b", "c:d", "trailing.", "trailing ", " ", "Überweisung", "*?\"<>|")
        SftpPath(locationA, names).segments shouldBe names
    }

    @Test
    fun `only the POSIX rule rejects a segment`() {
        listOf("", ".", "..", "a/b", "a\u0000b").forEach { segment ->
            shouldThrow<IllegalArgumentException> { SftpPath(locationA, listOf(segment)) }
        }
    }

    @Test
    fun `paths under different locations are unrelated`() {
        val a = SftpPath(locationA, listOf("photos"))
        val b = SftpPath(locationB, listOf("photos"))

        a.matches(b) shouldBe false
        SftpPath.root(locationA).isAncestorOf(b) shouldBe false
        listOf(a.child("1"), b.child("2")).commonParent() shouldBe null
        listOf(a.child("1"), a.child("2")).commonParent() shouldBe a
    }

    @Test
    fun `a known location is shown by its name`() {
        NetworkLocationNames.updateSftp(listOf(testSftpLocation(locationA, host = "cnc-dev")))

        val root = SftpPath.root(locationA)
        root.userReadablePath.get(context) shouldBe "sftp://cnc-dev"
        root.userReadableName.get(context) shouldBe "cnc-dev"

        val nested = SftpPath(locationA, listOf("usr", "bin"))
        nested.userReadablePath.get(context) shouldBe "sftp://cnc-dev/usr/bin"
        nested.userReadableName.get(context) shouldBe "bin"
    }

    @Test
    fun `a label wins over the host`() {
        NetworkLocationNames.updateSftp(listOf(testSftpLocation(locationA, host = "cnc-dev").copy(label = "Build box")))

        SftpPath(locationA, listOf("usr", "bin")).userReadablePath.get(context) shouldBe "sftp://Build box/usr/bin"
    }

    @Test
    fun `an unknown location is shown by its id`() {
        NetworkLocationNames.updateSftp(listOf(testSftpLocation(locationB, host = "cnc-dev")))

        val root = SftpPath.root(locationA)
        root.userReadablePath.get(context) shouldBe "sftp://11111111-1111-1111-1111-111111111111"
        root.userReadableName.get(context) shouldBe "11111111-1111-1111-1111-111111111111"
        SftpPath(locationA, listOf("usr", "bin")).userReadablePath.get(context) shouldBe
            "sftp://11111111-1111-1111-1111-111111111111/usr/bin"
    }

    @Test
    fun `the path string keeps the id whether or not the name is known`() {
        val root = SftpPath.root(locationA)
        val nested = SftpPath(locationA, listOf("usr", "bin"))
        root.path shouldBe "sftp://11111111-1111-1111-1111-111111111111"
        nested.path shouldBe "sftp://11111111-1111-1111-1111-111111111111/usr/bin"

        NetworkLocationNames.updateSftp(listOf(testSftpLocation(locationA, host = "cnc-dev")))

        root.path shouldBe "sftp://11111111-1111-1111-1111-111111111111"
        root.name shouldBe "11111111-1111-1111-1111-111111111111"
        nested.path shouldBe "sftp://11111111-1111-1111-1111-111111111111/usr/bin"
    }
}
