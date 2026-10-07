package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.saf.location.SAFLocation
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class WritabilityEvaluatorTest : BaseTest() {

    private val evaluator = WritabilityEvaluator()

    private val appUid = 10123

    private fun permissions(mode: Int) = Permissions(mode)

    private fun ownership(userId: Long, groupId: Long) = Ownership(userId, groupId)

    // ═══════════════════════════════════════════════════════════════
    // Rule 1: Elevated Access for Local Paths
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ElevatedAccess {

        @Test
        fun `root access makes local paths writable`() {
            val path = LocalPath.build("/system/etc/hosts")
            val context = WritabilityContext(
                hasRoot = true,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, null, null, context) shouldBe true
        }

        @Test
        fun `adb access makes local paths writable`() {
            val path = LocalPath.build("/system/etc/hosts")
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = true,
                appUid = appUid,
            )

            evaluator.evaluate(path, null, null, context) shouldBe true
        }

        @Test
        fun `elevated access ignores unix permissions for local paths`() {
            val path = LocalPath.build("/system/etc/hosts")
            val readOnlyPermissions = permissions(0b100_100_100) // r--r--r--
            val context = WritabilityContext(
                hasRoot = true,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, readOnlyPermissions, null, context) shouldBe true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Rule 2: SAF Paths
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class SafPaths {

        @Test
        fun `SAF path with write permission returns true`() {
            val safLocation = mockk<SAFLocation> {
                every { hasWritePermission } returns true
            }
            val path = mockk<SAFPath>()
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
                safLocation = safLocation,
            )

            evaluator.evaluate(path, null, null, context) shouldBe true
        }

        @Test
        fun `SAF path without write permission returns false`() {
            val safLocation = mockk<SAFLocation> {
                every { hasWritePermission } returns false
            }
            val path = mockk<SAFPath>()
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
                safLocation = safLocation,
            )

            evaluator.evaluate(path, null, null, context) shouldBe false
        }

        @Test
        fun `SAF path with null safLocation returns null`() {
            val path = mockk<SAFPath>()
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
                safLocation = null,
            )

            evaluator.evaluate(path, null, null, context) shouldBe null
        }

        @Test
        fun `elevated access does not override SAF permission check`() {
            val safLocation = mockk<SAFLocation> {
                every { hasWritePermission } returns false
            }
            val path = mockk<SAFPath>()
            val context = WritabilityContext(
                hasRoot = true,
                hasAdb = true,
                appUid = appUid,
                safLocation = safLocation,
            )

            // SAF paths use SAF permissions, not elevated access
            evaluator.evaluate(path, null, null, context) shouldBe false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Rule 3: Unknown Permissions
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class UnknownPermissions {

        @Test
        fun `null permissions returns null`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, null, null, context) shouldBe null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Rule 4: Unix Permission Evaluation
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class UnixPermissions {

        @Test
        fun `owner with write permission returns true`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val perms = permissions(0b110_000_000) // rw-------
            val owner = ownership(appUid.toLong(), 9999)
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, perms, owner, context) shouldBe true
        }

        @Test
        fun `owner without write permission returns false`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val perms = permissions(0b100_000_000) // r--------
            val owner = ownership(appUid.toLong(), 9999)
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, perms, owner, context) shouldBe false
        }

        @Test
        fun `group member with write permission returns true`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val perms = permissions(0b000_110_000) // ---rw----
            val owner = ownership(0, appUid.toLong()) // group matches app UID
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, perms, owner, context) shouldBe true
        }

        @Test
        fun `group member without write permission returns false`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val perms = permissions(0b000_100_000) // ---r-----
            val owner = ownership(0, appUid.toLong())
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, perms, owner, context) shouldBe false
        }

        @Test
        fun `others with write permission returns true`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val perms = permissions(0b000_000_110) // ------rw-
            val owner = ownership(0, 0) // neither owner nor group
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, perms, owner, context) shouldBe true
        }

        @Test
        fun `others without write permission returns false`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val perms = permissions(0b000_000_100) // ------r--
            val owner = ownership(0, 0)
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, perms, owner, context) shouldBe false
        }

        @Test
        fun `null ownership falls back to others permission`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val perms = permissions(0b000_000_110) // ------rw-
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, perms, null, context) shouldBe true
        }

        @Test
        fun `typical file permissions 644 not writable by others`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val perms = permissions(0b110_100_100) // rw-r--r-- (644)
            val owner = ownership(0, 0) // not owner, not group
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, perms, owner, context) shouldBe false
        }

        @Test
        fun `world writable 777 is writable by others`() {
            val path = LocalPath.build("/sdcard/test.txt")
            val perms = permissions(0b111_111_111) // rwxrwxrwx (777)
            val owner = ownership(0, 0)
            val context = WritabilityContext(
                hasRoot = false,
                hasAdb = false,
                appUid = appUid,
            )

            evaluator.evaluate(path, perms, owner, context) shouldBe true
        }
    }

    @Nested
    inner class NetworkPaths {

        private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

        private val context = WritabilityContext(
            hasRoot = false,
            hasAdb = false,
            appUid = appUid,
        )

        @Test
        fun `an SMB path is left to the server`() {
            evaluator.evaluate(SmbPath(locationId, listOf("media")), null, null, context) shouldBe true
        }

        /** The listed owner is an account on the server, the app's uid says nothing about it. */
        @Test
        fun `an SFTP path is left to the server, whatever its mode bits say`() {
            val path = SftpPath(locationId, listOf("srv", "readonly.txt"))

            evaluator.evaluate(path, permissions(0b100_100_100), ownership(0, 0), context) shouldBe true
            evaluator.evaluate(path, null, null, context) shouldBe true
        }
    }
}
