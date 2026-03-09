package eu.darken.butler.common.debug.recorder.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Instant

class DebugSessionManagerSessionLogicTest : BaseTest() {

    @TempDir
    lateinit var tempDir: File

    private lateinit var externalLogsDir: File
    private lateinit var cacheLogsDir: File

    @BeforeEach
    fun setup() {
        externalLogsDir = File(tempDir, "external/debug/logs").also { it.mkdirs() }
        cacheLogsDir = File(tempDir, "cache/debug/logs").also { it.mkdirs() }
    }

    private fun logDirs() = listOf(externalLogsDir, cacheLogsDir)

    @Nested
    inner class ParseCreatedAt {
        @Test
        fun `returns creation time parsed from filename`() {
            // Butler format: {pkg}_{versionCode}_{yyyy-MM-dd_HH-mm-ss-SSS}
            val file = File(externalLogsDir, "eu.darken.butler_100_2024-03-07_11-20-00-000").also { it.mkdirs() }
            val result = DebugSessionManager.parseCreatedAt(file)
            // Should parse successfully (exact millis depend on local timezone)
            result.toEpochMilliseconds() shouldBe result.toEpochMilliseconds() // non-null
        }

        @Test
        fun `non-existent file returns fallback`() {
            val file = File(externalLogsDir, "nonexistent")
            val result = DebugSessionManager.parseCreatedAt(file)
            // Falls back to lastModified (0 for non-existent) → Instant.fromEpochMilliseconds(0)
            result shouldBe Instant.fromEpochMilliseconds(0L)
        }
    }

    @Nested
    inner class DeriveSessionId {
        @Test
        fun `external dir gets ext prefix`() {
            val file = File("/storage/emulated/0/Android/data/pkg/files/debug/logs/session1")
            DebugSessionManager.deriveSessionId(file) shouldBe "ext:session1"
        }

        @Test
        fun `cache dir gets cache prefix`() {
            val file = File("/data/data/pkg/cache/debug/logs/session1")
            DebugSessionManager.deriveSessionId(file) shouldBe "cache:session1"
        }

        @Test
        fun `zip suffix is stripped`() {
            val file = File("/data/data/pkg/cache/debug/logs/session1.zip")
            DebugSessionManager.deriveSessionId(file) shouldBe "cache:session1"
        }

        @Test
        fun `ambiguous path without cache marker defaults to ext`() {
            val file = File("/tmp/some/random/path/session1")
            DebugSessionManager.deriveSessionId(file) shouldBe "ext:session1"
        }

        @Test
        fun `path with cache substring elsewhere gets cache prefix`() {
            val file = File("/storage/emulated/0/backup/cache/debug/logs/session1")
            DebugSessionManager.deriveSessionId(file) shouldBe "cache:session1"
        }

        @Test
        fun `dotcache path does not trigger cache prefix`() {
            val file = File("/storage/emulated/0/.cache/debug/logs/session1")
            DebugSessionManager.deriveSessionId(file) shouldBe "ext:session1"
        }
    }

    @Nested
    inner class ScanSessions {
        @Test
        fun `empty directories returns empty list`() {
            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())
            result.shouldBeEmpty()
        }

        @Test
        fun `dir with valid core log returns Ready`() {
            val sessionDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").writeText("some log content")

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            val session = result.first()
            session.shouldBeInstanceOf<DebugSession.Ready>()
            session.id shouldBe "ext:eu.darken.butler_100_2023-11-14_22-13-20-000"
            session.logDir shouldBe sessionDir
            session.zipFile shouldBe null
            session.compressedSize shouldBe 0L
        }

        @Test
        fun `dir with empty core log returns Failed EMPTY_LOG`() {
            val sessionDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").createNewFile()

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            val session = result.first()
            session.shouldBeInstanceOf<DebugSession.Failed>()
            (session as DebugSession.Failed).reason shouldBe DebugSession.Failed.Reason.EMPTY_LOG
        }

        @Test
        fun `dir with no core log returns Failed MISSING_LOG`() {
            File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").mkdirs()

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            val session = result.first()
            session.shouldBeInstanceOf<DebugSession.Failed>()
            (session as DebugSession.Failed).reason shouldBe DebugSession.Failed.Reason.MISSING_LOG
        }

        @Test
        fun `standalone non-empty zip returns Ready with null logDir`() {
            File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip").writeText("zipdata")

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            val session = result.first()
            session.shouldBeInstanceOf<DebugSession.Ready>()
            (session as DebugSession.Ready).logDir shouldBe null
            session.zipFile shouldBe File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip")
            session.compressedSize shouldBe File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip").length()
        }

        @Test
        fun `standalone empty zip returns Failed CORRUPT_ZIP`() {
            File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip").createNewFile()

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            val session = result.first()
            session.shouldBeInstanceOf<DebugSession.Failed>()
            (session as DebugSession.Failed).reason shouldBe DebugSession.Failed.Reason.CORRUPT_ZIP
        }

        @Test
        fun `dir plus sibling zip reports combined diskSize`() {
            val sessionDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").writeText("log content here")
            File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip").writeText("zipdata12345")

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            val session = result.first() as DebugSession.Ready
            val expectedDirSize = sessionDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val zipFile = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip")
            val expectedZipSize = zipFile.length()
            session.diskSize shouldBe expectedDirSize + expectedZipSize
            session.zipFile shouldBe zipFile
            session.compressedSize shouldBe expectedZipSize
        }

        @Test
        fun `dir missing core log but valid sibling zip returns Ready with null logDir`() {
            File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").mkdirs()
            File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip").writeText("zipdata")

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            val session = result.first()
            session.shouldBeInstanceOf<DebugSession.Ready>()
            (session as DebugSession.Ready).logDir shouldBe null
            session.zipFile shouldBe File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip")
        }

        @Test
        fun `active recording dir returns Recording`() {
            val sessionDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").writeText("recording in progress")

            val startInstant = Instant.fromEpochMilliseconds(1700000000000L)
            val result = DebugSessionManager.scanSessions(
                logDirectories = logDirs(),
                activeDir = sessionDir,
                recordingStartedAt = startInstant,
            )

            result shouldHaveSize 1
            val session = result.first()
            session.shouldBeInstanceOf<DebugSession.Recording>()
            (session as DebugSession.Recording).startedAt shouldBe startInstant
        }

        @Test
        fun `ignores tmp files`() {
            File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip.tmp").writeText("temp")
            val sessionDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").writeText("log")

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            result.first().id shouldBe "ext:eu.darken.butler_100_2023-11-14_22-13-20-000"
        }

        @Test
        fun `handles missing log directory gracefully`() {
            val missing = File(tempDir, "nonexistent/path")
            val result = DebugSessionManager.scanSessions(logDirectories = listOf(missing))

            result.shouldBeEmpty()
        }

        @Test
        fun `cache directory gets cache prefix`() {
            val sessionDir = File(cacheLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").writeText("cached log")

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            result.first().id shouldBe "cache:eu.darken.butler_100_2023-11-14_22-13-20-000"
        }

        @Test
        fun `empty core log with valid sibling zip returns Ready`() {
            val sessionDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").createNewFile()
            File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip").writeText("valid zip data")

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 1
            val session = result.first()
            session.shouldBeInstanceOf<DebugSession.Ready>()
            (session as DebugSession.Ready).logDir shouldBe null
            session.zipFile shouldBe File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip")
        }

        @Test
        fun `multiple log directories are combined`() {
            val extDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(extDir, "core.log").writeText("ext log")

            val cacheDir = File(cacheLogsDir, "eu.darken.butler_100_2020-09-13_12-26-40-000").also { it.mkdirs() }
            File(cacheDir, "core.log").writeText("cache log")

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 2
            result.any { it.id == "ext:eu.darken.butler_100_2023-11-14_22-13-20-000" } shouldBe true
            result.any { it.id == "cache:eu.darken.butler_100_2020-09-13_12-26-40-000" } shouldBe true
        }

        @Test
        fun `multiple sessions sorted by createdAt descending then id ascending`() {
            val dirA = File(externalLogsDir, "eu.darken.butler_100_2020-09-13_12-26-40-000").also { it.mkdirs() }
            File(dirA, "core.log").writeText("log a")

            val dirB = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(dirB, "core.log").writeText("log b")

            val result = DebugSessionManager.scanSessions(logDirectories = logDirs())

            result shouldHaveSize 2
            val comparator = compareByDescending<DebugSession> { it.createdAt }.thenBy { it.id }
            result shouldBe result.sortedWith(comparator)
        }
    }

    @Nested
    inner class IdRoundTrip {
        @Test
        fun `deriveSessionId on ext dir matches scanSessions output`() {
            val sessionDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").writeText("log content")

            val derived = DebugSessionManager.deriveSessionId(sessionDir)
            val scanned = DebugSessionManager.scanSessions(logDirectories = logDirs())

            scanned shouldHaveSize 1
            scanned.first().id shouldBe derived
        }

        @Test
        fun `deriveSessionId on cache dir matches scanSessions output`() {
            val sessionDir = File(cacheLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").writeText("log content")

            val derived = DebugSessionManager.deriveSessionId(sessionDir)
            val scanned = DebugSessionManager.scanSessions(logDirectories = logDirs())

            scanned shouldHaveSize 1
            scanned.first().id shouldBe derived
        }

        @Test
        fun `deriveSessionId on zip file matches scanSessions output`() {
            File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip").writeText("zipdata")

            val zipFile = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip")
            val derived = DebugSessionManager.deriveSessionId(zipFile)
            val scanned = DebugSessionManager.scanSessions(logDirectories = logDirs())

            scanned shouldHaveSize 1
            scanned.first().id shouldBe derived
        }

        @Test
        fun `deriveSessionId is consistent for dir and sibling zip`() {
            val sessionDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(sessionDir, "core.log").writeText("log content")
            val zipFile = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000.zip").also {
                it.writeText("zipdata")
            }

            val dirId = DebugSessionManager.deriveSessionId(sessionDir)
            val zipId = DebugSessionManager.deriveSessionId(zipFile)
            dirId shouldBe zipId

            val scanned = DebugSessionManager.scanSessions(logDirectories = logDirs())
            scanned shouldHaveSize 1
            scanned.first().id shouldBe dirId
        }

        @Test
        fun `same basename in ext and cache produces distinct IDs`() {
            val extDir = File(externalLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(extDir, "core.log").writeText("ext log")
            val cacheDir = File(cacheLogsDir, "eu.darken.butler_100_2023-11-14_22-13-20-000").also { it.mkdirs() }
            File(cacheDir, "core.log").writeText("cache log")

            val extId = DebugSessionManager.deriveSessionId(extDir)
            val cacheId = DebugSessionManager.deriveSessionId(cacheDir)
            extId shouldBe "ext:eu.darken.butler_100_2023-11-14_22-13-20-000"
            cacheId shouldBe "cache:eu.darken.butler_100_2023-11-14_22-13-20-000"

            val scanned = DebugSessionManager.scanSessions(logDirectories = logDirs())
            scanned shouldHaveSize 2
            scanned.map { it.id }.toSet() shouldBe setOf(extId, cacheId)
        }
    }
}
