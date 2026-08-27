package eu.darken.butler.common.pkgs.installer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.archive.ArchiveEntryMeta
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.ArchiveIndex
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import eu.darken.butler.common.pkgs.apk.ApkSignature
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.shell.ShellOps
import eu.darken.butler.common.shell.ShellOpsException
import eu.darken.butler.common.shell.ipc.ShellOpsCmd
import eu.darken.butler.common.shell.ipc.ShellOpsResult
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserManager2
import eu.darken.butler.common.user.UserProfile2
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppInstallerTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcherProvider = TestDispatcherProvider()

    private lateinit var workDir: File
    private lateinit var apk: LocalPath

    /** Every shell line the installer ran, in order, paired with the mode it ran in. */
    private val executed = mutableListOf<Pair<ShellOps.Mode, String>>()

    /** Paths the installer created or wrote through the gateway. */
    private val gatewayWrites = mutableListOf<String>()

    private var rootTransportBroken = false
    private var commitSucceeds = true
    private var commitAnswersOnStderr = false
    private var writeSucceeds = true
    private var sessionCreateOutput = listOf("Success: created install session [42]")
    private var sessionCreateErrors = emptyList<String>()

    /** The mode whose `pm install-create` answers success in a shape the installer cannot read. */
    private var unreadableCreateMode: ShellOps.Mode? = null

    /** What the shell staging root holds, the root itself included when it exists. */
    private var shellStagingChildren = emptyList<String>()

    /** Makes the removal of a run's own staging directory fail, leaving it behind. */
    private var stagingRemovalFails = false

    /** What the expansion destination directory holds when a run looks at it. */
    private var obbDirChildren = emptyList<String>()

    /** Makes writing an expansion partial fail, as a lost elevated connection would. */
    private var obbStagingFails = false

    /** Makes every removal through the gateway fail, so nothing a run wrote can be cleaned up. */
    private var gatewayRemovalFails = false

    /** Paths a run tried to remove through the gateway, whether or not the removal worked. */
    private val gatewayRemovals = mutableListOf<String>()

    private val systemInstallGate = SystemInstallGate()

    /** Platform sessions this package owns, as an earlier process would have left them behind. */
    private var systemSessionIds = mutableSetOf<Int>()

    /** Makes listing the platform sessions fail, as a dead installer service would. */
    private var sessionListingFails = false

    /** Whether abandoning a platform session works or throws, as a dead installer service would. */
    private var abandonBehaviour = AbandonBehaviour.WORKS

    /** What a system run did in which order, for the steps whose order is what is being checked. */
    private val timeline = mutableListOf<String>()

    private val shellOps = mockk<ShellOps>()
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val archiveService = mockk<ArchiveService>()
    private val apkArchiveParser = mockk<ApkArchiveParser>()
    private val storageEnvironment = mockk<StorageEnvironment>(relaxed = true)
    private val systemInstallSessions = mockk<SystemInstallSessions>()

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "installer_test").apply {
            deleteRecursively()
            mkdirs()
        }
        apk = LocalPath.build(File(workDir, "demo.apk").apply { writeText("apk-bytes") })

        coEvery { shellOps.execute(any(), any()) } answers {
            val mode = secondArg<ShellOps.Mode>()
            val line = firstArg<ShellOpsCmd>().cmds.single()
            executed += mode to line
            if (rootTransportBroken && mode == ShellOps.Mode.ROOT) throw ShellOpsException("no root transport")
            when {
                line.startsWith("pm install-create") -> when (mode) {
                    unreadableCreateMode -> ShellOpsResult(0, listOf("Success: created install session"), emptyList())
                    else -> ShellOpsResult(0, sessionCreateOutput, sessionCreateErrors)
                }
                line.startsWith("pm install-write") -> when {
                    writeSucceeds -> ShellOpsResult(0, listOf("Success: streamed"), emptyList())
                    else -> ShellOpsResult(1, emptyList(), listOf("Error: INSTALL_FAILED_INVALID_APK"))
                }

                line.startsWith("pm install-commit") -> when {
                    !commitSucceeds -> ShellOpsResult(1, emptyList(), listOf("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
                    commitAnswersOnStderr -> ShellOpsResult(0, emptyList(), listOf("Success"))
                    else -> ShellOpsResult(0, listOf("Success"), emptyList())
                }

                line.startsWith("rm -rf") -> when {
                    stagingRemovalFails && !line.contains(LEFTOVER_NAME) ->
                        ShellOpsResult(1, emptyList(), listOf("rm: Permission denied"))

                    else -> ShellOpsResult(0, emptyList(), emptyList())
                }

                else -> ShellOpsResult(0, emptyList(), emptyList())
            }
        }

        every { systemInstallSessions.sessionIds() } answers {
            timeline += "sessions"
            if (sessionListingFails) throw SecurityException("Package installer unavailable")
            systemSessionIds.toList()
        }
        every { systemInstallSessions.abandon(any()) } answers {
            val sessionId = firstArg<Int>()
            timeline += "abandon:$sessionId"
            when (abandonBehaviour) {
                AbandonBehaviour.WORKS -> systemSessionIds.remove(sessionId)
                AbandonBehaviour.THROWS -> throw SecurityException("Session $sessionId is not yours")
            }
        }

        coEvery { gatewaySwitch.openInputStream(any()) } answers {
            timeline += "stage"
            firstArg<LocalPath>().file.inputStream()
        }
        coEvery { gatewaySwitch.createDir(any(), any()) } answers { gatewayWrites += firstArg<LocalPath>().path }
        coEvery { gatewaySwitch.createFile(any(), any()) } answers {
            val path = firstArg<LocalPath>().path
            gatewayWrites += path
            if (obbStagingFails && path.endsWith(PARTIAL_SUFFIX)) throw IOException("No elevated access")
        }
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } answers { ByteArrayOutputStream() }
        coEvery { gatewaySwitch.exists(any()) } answers {
            val path = firstArg<LocalPath>().path
            path in shellStagingChildren || path in obbDirChildren || path.endsWith(PARTIAL_SUFFIX)
        }
        coEvery { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) } answers {
            gatewayRemovals += firstArg<LocalPath>().path
            if (gatewayRemovalFails) throw IOException("No elevated access")
            true
        }
        coEvery { gatewaySwitch.canonicalize(any()) } answers { firstArg<LocalPath>() }
        coEvery { gatewaySwitch.listFiles(any()) } answers {
            val parent = firstArg<LocalPath>().path
            when (parent) {
                OBB_DIR -> obbDirChildren.map { LocalPath.build(it) }
                else -> shellStagingChildren.filter { it != parent }.map { LocalPath.build(it) }
            }
        }
        every { storageEnvironment.publicObbDirs } returns listOf(LocalPath.build(OBB_ROOT))
    }

    @After
    fun teardown() {
        workDir.deleteRecursively()
    }

    private fun installer(root: Boolean = true, adb: Boolean = true) = AppInstaller(
        context = context,
        shellOps = shellOps,
        rootManager = mockk<RootManager> { every { useRoot } returns flowOf(root) },
        adbManager = mockk<AdbManager> { every { useAdb } returns flowOf(adb) },
        userManager2 = mockk<UserManager2> {
            coEvery { currentUser() } returns UserProfile2(handle = UserHandle2(handleId = 11))
        },
        gatewaySwitch = gatewaySwitch,
        archiveService = archiveService,
        apkArchiveParser = apkArchiveParser,
        storageEnvironment = storageEnvironment,
        statusRelay = AppInstallStatusRelay(),
        systemInstallGate = systemInstallGate,
        systemInstallSessions = systemInstallSessions,
        dispatcherProvider = dispatcherProvider,
    )

    private fun plan() = AppInstallPlan(
        source = apk,
        format = AppInstallFormat.APK,
        pkgId = "com.example.app".toPkgId(),
        baseInfo = null,
        splits = listOf(
            AppInstallPlan.Split(entryPath = "demo.apk", stagedName = "base.apk", size = apk.file.length()),
        ),
        obbEntries = emptyList(),
        warnings = emptyList(),
    )

    /** A plain APK behind a provider that reports no size, as SAF providers may. */
    private fun sizelessPlan(inspected: ApkArchiveInfo) = plan().let {
        it.copy(baseInfo = inspected, splits = listOf(it.splits.single().copy(size = 0L)))
    }

    private fun pmCommands() = executed.map { it.second }.filter { it.startsWith("pm ") }

    private fun baseInfo(certSha256: String) = ApkArchiveInfo(
        id = "com.example.app".toPkgId(),
        versionCode = 3,
        signatures = listOf(ApkSignature(subjectDn = null, sha256 = certSha256)),
    )

    /** A one-APK bundle whose single entry is [apk], so the staged base is readable back. */
    private fun bundlePlan(inspected: ApkArchiveInfo) = AppInstallPlan(
        source = apk,
        format = AppInstallFormat.XAPK,
        pkgId = inspected.id,
        baseInfo = inspected,
        splits = listOf(
            AppInstallPlan.Split(entryPath = "base.apk", stagedName = "base.apk", size = apk.file.length()),
        ),
        obbEntries = emptyList(),
        warnings = emptyList(),
        indexEntryCount = 1,
    )

    /** The same bundle plus one expansion payload, which only elevated access can place. */
    private fun obbBundlePlan(inspected: ApkArchiveInfo) = bundlePlan(inspected).copy(
        obbEntries = listOf(
            AppInstallPlan.ObbEntry(entryPath = OBB_NAME, fileName = OBB_NAME, size = apk.file.length()),
        ),
        warnings = listOf(AppInstallPlan.Warning.OBB_PRESENT),
        indexEntryCount = 2,
    )

    private fun stubBundleArchive(withObb: Boolean = false) {
        val entries = buildList {
            add(entryMeta("base.apk"))
            if (withObb) add(entryMeta(OBB_NAME))
        }
        coEvery { archiveService.invalidate(any()) } returns Unit
        coEvery { archiveService.index(any()) } returns ArchiveIndex(
            container = apk,
            format = ArchiveFormat.ZIP,
            fingerprint = "fingerprint",
            entriesBySegments = entries.associateBy { it.segments },
            childrenBySegments = emptyMap(),
            skippedUnsafe = 0,
            skippedSpecial = 0,
        )
        coEvery { archiveService.useEntryStreams(any(), any(), any()) } coAnswers {
            val wanted = secondArg<Collection<ArchiveEntryMeta>>()
            val action = thirdArg<suspend (ArchiveEntryMeta, InputStream) -> Unit>()
            wanted.forEach { meta -> apk.file.inputStream().use { action(meta, it) } }
        }
    }

    /** Every entry is [apk], so anything the installer stages is readable back as one. */
    private fun entryMeta(name: String) = ArchiveEntryMeta(
        segments = listOf(name),
        rawName = name,
        isDirectory = false,
        size = apk.file.length(),
        modifiedAt = null,
    )

    @Test
    fun `root install runs create, write and commit in order`() = runTest2 {
        val events = installer().install(plan(), AppInstaller.Mode.ROOT).toList()

        val success = events.last().shouldBeInstanceOf<AppInstallEvent.Success>()
        success.viaMode shouldBe AppInstaller.Mode.ROOT
        success.obbPlaced shouldBe false

        val commands = pmCommands()
        commands.size shouldBe 3
        // The user comes from UserManager2, not from a hardcoded owner id.
        commands[0] shouldBe "pm install-create -r -t -S ${apk.file.length()} --user 11"
        commands[1] shouldContain "pm install-write -S ${apk.file.length()} 42 'base.apk'"
        commands[2] shouldBe "pm install-commit 42"
        executed.none { it.first == ShellOps.Mode.ADB } shouldBe true
    }

    @Test
    fun `root staging lives in the app cache, adb staging in the shell tmp dir`() = runTest2 {
        installer().install(plan(), AppInstaller.Mode.ROOT).toList()
        val rootStaged = pmCommands().single { it.startsWith("pm install-write") }
        rootStaged shouldContain "${context.cacheDir}/install-staging/"

        executed.clear()
        gatewayWrites.clear()

        installer().install(plan(), AppInstaller.Mode.ADB).toList()
        val adbStaged = pmCommands().single { it.startsWith("pm install-write") }
        // Owned by the shell UID, which is exactly the shell that runs pm right after.
        adbStaged shouldContain "/data/local/tmp/butler-install/"
        gatewayWrites.any { it.startsWith("/data/local/tmp/butler-install/") } shouldBe true
    }

    @Test
    fun `the staged name is generated, never the archive name`() = runTest2 {
        val archiveNamed = plan().let {
            it.copy(
                splits = listOf(it.splits.single().copy(entryPath = "'; rm -rf /; echo 'pwned.apk")),
            )
        }

        installer().install(archiveNamed, AppInstaller.Mode.ROOT).toList()

        val write = pmCommands().single { it.startsWith("pm install-write") }
        write shouldContain "'base.apk'"
        executed.none { it.second.contains("rm -rf /;") } shouldBe true
    }

    @Test
    fun `a failed write abandons the session`() = runTest2 {
        writeSucceeds = false

        val events = installer().install(plan(), AppInstaller.Mode.ROOT).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Failure>()
            .error.shouldBeInstanceOf<AppInstallSessionException>()
        pmCommands().last() shouldBe "pm install-abandon 42"
    }

    @Test
    fun `an unparseable session id is an error, never a silent success`() = runTest2 {
        sessionCreateOutput = listOf("Success: created install session")

        val events = installer().install(plan(), AppInstaller.Mode.ROOT).toList()

        // Unreadable, not rejected: the APK was never judged, so this must not end the run for the
        // other modes when they are still available.
        events.last().shouldBeInstanceOf<AppInstallEvent.Failure>()
            .error.shouldBeInstanceOf<AppInstallTransportException>()
        pmCommands() shouldContainExactly listOf("pm install-create -r -t -S ${apk.file.length()} --user 11")
    }

    @Test
    fun `a create response on stderr still yields the session`() = runTest2 {
        sessionCreateOutput = emptyList()
        sessionCreateErrors = listOf("Success: created install session [42]")

        val events = installer().install(plan(), AppInstaller.Mode.ROOT).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Success>().viaMode shouldBe AppInstaller.Mode.ROOT
        pmCommands().last() shouldBe "pm install-commit 42"
    }

    @Test
    fun `a commit response on stderr is still a success`() = runTest2 {
        commitAnswersOnStderr = true

        val events = installer().install(plan(), AppInstaller.Mode.ROOT).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Success>().viaMode shouldBe AppInstaller.Mode.ROOT
        // The session is committed, so abandoning it afterwards would be undoing a finished install.
        pmCommands().none { it.startsWith("pm install-abandon") } shouldBe true
    }

    @Test
    fun `an unreadable create response falls through to the next mode`() = runTest2 {
        unreadableCreateMode = ShellOps.Mode.ROOT

        val events = installer().install(plan(), AppInstaller.Mode.AUTO).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Success>().viaMode shouldBe AppInstaller.Mode.ADB
    }

    @Test
    fun `a broken transport falls through to the next mode`() = runTest2 {
        rootTransportBroken = true

        val events = installer().install(plan(), AppInstaller.Mode.AUTO).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Success>().viaMode shouldBe AppInstaller.Mode.ADB
    }

    @Test
    fun `a semantic failure is terminal and never retried elsewhere`() = runTest2 {
        commitSucceeds = false

        val events = installer().install(plan(), AppInstaller.Mode.AUTO).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Failure>()
            .error.shouldBeInstanceOf<AppInstallSessionException>()
        // Root ran the session; ADB must not get a second chance at an APK the system rejected.
        executed.none { it.first == ShellOps.Mode.ADB } shouldBe true
    }

    @Test
    fun `an explicitly requested mode without elevation fails instead of falling back`() = runTest2 {
        val events = installer(root = false, adb = false).install(plan(), AppInstaller.Mode.ROOT).toList()

        events.single().shouldBeInstanceOf<AppInstallEvent.Failure>()
            .error.shouldBeInstanceOf<AppInstallNoElevationException>()
        executed.isEmpty() shouldBe true
    }

    @Test
    fun `a system install is refused while another one holds the installer`() = runTest2 {
        systemInstallGate.claim("Pending App")

        val events = installer().install(plan(), AppInstaller.Mode.SYSTEM).toList()

        events.single().shouldBeInstanceOf<AppInstallEvent.Failure>()
            .error.shouldBeInstanceOf<AppInstallBusyException>()
            .pendingLabel shouldBe "Pending App"
        // Android answers one confirmation at a time and answers it against the session that asked
        // first, so a second session must not exist at all - not even its staging.
        File(context.cacheDir, "install-staging").listFiles()?.toList().orEmpty().shouldBeEmpty()
    }

    @Test
    fun `an elevated install runs while the system installer is held`() = runTest2 {
        systemInstallGate.claim("Pending App")

        val events = installer().install(plan(), AppInstaller.Mode.ROOT).toList()

        // Nothing to contend over: `pm` installs without ever asking the user to confirm.
        events.last().shouldBeInstanceOf<AppInstallEvent.Success>().viaMode shouldBe AppInstaller.Mode.ROOT
    }

    @Test
    fun `a system install that failed hands the installer back`() = runTest2 {
        coEvery { gatewaySwitch.openInputStream(any()) } throws IOException("Source is gone")

        installer().install(plan(), AppInstaller.Mode.SYSTEM).toList()
            .last().shouldBeInstanceOf<AppInstallEvent.Failure>()

        systemInstallGate.claim("Next App").shouldBeInstanceOf<SystemInstallGate.Outcome.Granted>()
    }

    @Test
    fun `a session an earlier process left behind is abandoned before anything is staged`() = runTest2 {
        systemSessionIds = mutableSetOf(77)
        coEvery { gatewaySwitch.openInputStream(any()) } answers {
            timeline += "stage"
            throw IOException("Source is gone")
        }

        installer().install(plan(), AppInstaller.Mode.SYSTEM).toList()

        // The gate lives in memory, the session outlives the process holding it: the survivor has to
        // be gone before this run reads a byte, let alone opens the session it would be answered for.
        timeline shouldContainExactly listOf("sessions", "abandon:77", "stage")
    }

    @Test
    fun `surviving sessions are cleared once per process, not once per install`() = runTest2 {
        coEvery { gatewaySwitch.openInputStream(any()) } answers {
            timeline += "stage"
            throw IOException("Source is gone")
        }
        val installer = installer()

        installer.install(plan(), AppInstaller.Mode.SYSTEM).toList()
        timeline shouldContainExactly listOf("sessions", "stage")

        timeline.clear()
        // Only a session from before this process started can be orphaned, and this one is not: it
        // would belong to an install this very process is running.
        systemSessionIds = mutableSetOf(88)

        installer.install(plan(), AppInstaller.Mode.SYSTEM).toList()

        timeline shouldContainExactly listOf("stage")
    }

    @Test
    fun `an install runs when the surviving sessions cannot be listed`() = runTest2 {
        sessionListingFails = true
        coEvery { gatewaySwitch.openInputStream(any()) } answers {
            timeline += "stage"
            throw IOException("Source is gone")
        }

        val events = installer().install(plan(), AppInstaller.Mode.SYSTEM).toList()

        // Clearing orphans is housekeeping, so the run it was done for goes ahead and fails for its
        // own reason - here the source that disappeared, not the sessions that could not be read.
        events.last().shouldBeInstanceOf<AppInstallEvent.Failure>().error.shouldBeInstanceOf<IOException>()
        timeline shouldContainExactly listOf("sessions", "stage")
        systemInstallGate.claim("Next App").shouldBeInstanceOf<SystemInstallGate.Outcome.Granted>()
    }

    @Test
    fun `an install runs when a surviving session cannot be abandoned`() = runTest2 {
        systemSessionIds = mutableSetOf(77)
        abandonBehaviour = AbandonBehaviour.THROWS
        coEvery { gatewaySwitch.openInputStream(any()) } answers {
            timeline += "stage"
            throw IOException("Source is gone")
        }

        val events = installer().install(plan(), AppInstaller.Mode.SYSTEM).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Failure>().error.shouldBeInstanceOf<IOException>()
        timeline shouldContainExactly listOf("sessions", "abandon:77", "stage")
        systemInstallGate.claim("Next App").shouldBeInstanceOf<SystemInstallGate.Outcome.Granted>()
    }

    @Test
    fun `a sweep that could not clear a session is retried by the next install`() = runTest2 {
        systemSessionIds = mutableSetOf(77)
        abandonBehaviour = AbandonBehaviour.THROWS
        coEvery { gatewaySwitch.openInputStream(any()) } answers {
            timeline += "stage"
            throw IOException("Source is gone")
        }
        val installer = installer()

        installer.install(plan(), AppInstaller.Mode.SYSTEM).toList()
        timeline.clear()
        abandonBehaviour = AbandonBehaviour.WORKS

        installer.install(plan(), AppInstaller.Mode.SYSTEM).toList()

        timeline shouldContainExactly listOf("sessions", "abandon:77", "stage")
        systemSessionIds.shouldBeEmpty()
    }

    @Test
    fun `an elevated install neither looks at nor clears surviving sessions`() = runTest2 {
        systemSessionIds = mutableSetOf(77)

        val events = installer().install(plan(), AppInstaller.Mode.ROOT).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Success>().viaMode shouldBe AppInstaller.Mode.ROOT
        // `pm` never asks for a confirmation, so nothing here contends with a pending one.
        timeline shouldContainExactly listOf("stage")
        systemSessionIds shouldContain 77
    }

    @Test
    fun `a bundle whose staged base is not the inspected one is rejected`() = runTest2 {
        stubBundleArchive()
        val inspected = baseInfo(certSha256 = "aa")
        coEvery { apkArchiveParser.parseFile(any(), any()) } returns baseInfo(certSha256 = "bb")

        val events = installer().install(bundlePlan(inspected), AppInstaller.Mode.ROOT).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Failure>()
            .error.shouldBeInstanceOf<AppInstallUnsupportedBundleException>()
        // Rejected before a session exists, so nothing was ever handed to `pm`.
        pmCommands().isEmpty() shouldBe true
    }

    @Test
    fun `a bundle whose staged base is the inspected one installs`() = runTest2 {
        stubBundleArchive()
        val inspected = baseInfo(certSha256 = "aa")
        coEvery { apkArchiveParser.parseFile(any(), any()) } returns inspected

        val events = installer().install(bundlePlan(inspected), AppInstaller.Mode.ROOT).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Success>().viaMode shouldBe AppInstaller.Mode.ROOT
    }

    @Test
    fun `an APK whose size the provider never reported still installs`() = runTest2 {
        // SAF providers may leave COLUMN_SIZE unset, which the lookup folds to zero.
        val sizeless = plan().let { it.copy(splits = listOf(it.splits.single().copy(size = 0L))) }

        val events = installer().install(sizeless, AppInstaller.Mode.ROOT).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Success>()
        // Sized from what staging wrote, there being nothing declared to size it from.
        pmCommands()[0] shouldBe "pm install-create -r -t -S ${apk.file.length()} --user 11"
        pmCommands()[1] shouldContain "pm install-write -S ${apk.file.length()} 42"
    }

    @Test
    fun `an expansion partial that could not be removed stops being protected`() = runTest2 {
        stubBundleArchive(withObb = true)
        val inspected = baseInfo(certSha256 = "aa")
        coEvery { apkArchiveParser.parseFile(any(), any()) } returns inspected
        obbStagingFails = true
        gatewayRemovalFails = true
        val installer = installer()

        val events = installer.install(obbBundlePlan(inspected), AppInstaller.Mode.ROOT).toList()

        events.any { it is AppInstallEvent.ObbFailed } shouldBe true
        val partial = gatewayWrites.single { it.endsWith(PARTIAL_SUFFIX) }

        // What the failed run gave up on is what the next run finds in the destination.
        obbDirChildren = listOf(partial)
        gatewayRemovals.clear()

        installer.install(obbBundlePlan(inspected), AppInstaller.Mode.ROOT).toList()

        // Protected while its own run was still trying to remove it, sweepable once that run gave
        // up: staying registered would keep several GB under Android/obb for the whole process.
        gatewayRemovals shouldContain partial
    }

    @Test
    fun `an unknown-size APK staged for adb is held against the inspection`() = runTest2 {
        val inspected = baseInfo(certSha256 = "aa")
        coEvery { apkArchiveParser.parseFile(any(), any()) } returns baseInfo(certSha256 = "bb")

        val events = installer().install(sizelessPlan(inspected), AppInstaller.Mode.ADB).toList()

        // Shell staging cannot be read back, so without the local copy nothing would bind these
        // bytes to the inspected package at all.
        events.last().shouldBeInstanceOf<AppInstallEvent.Failure>()
            .error.shouldBeInstanceOf<AppInstallUnsupportedBundleException>()
        pmCommands().isEmpty() shouldBe true
    }

    @Test
    fun `an unknown-size APK staged for adb installs the bytes that were verified`() = runTest2 {
        val inspected = baseInfo(certSha256 = "aa")
        coEvery { apkArchiveParser.parseFile(any(), any()) } returns inspected

        val events = installer().install(sizelessPlan(inspected), AppInstaller.Mode.ADB).toList()

        events.last().shouldBeInstanceOf<AppInstallEvent.Success>().viaMode shouldBe AppInstaller.Mode.ADB
        pmCommands()[0] shouldBe "pm install-create -r -t -S ${apk.file.length()} --user 11"
        val write = pmCommands()[1]
        write shouldContain "pm install-write -S ${apk.file.length()} 42 'base.apk'"
        write shouldContain "/data/local/tmp/butler-install/"
        // The shell copy comes from the verified file: a second read of the provider could serve
        // something else entirely.
        coVerify(exactly = 1) { gatewaySwitch.openInputStream(apk) }
    }

    @Test
    fun `the shell sweep removes leftovers one by one, never the whole root`() = runTest2 {
        shellStagingChildren = listOf(SHELL_ROOT, "$SHELL_ROOT/$LEFTOVER_NAME")

        installer().install(plan(), AppInstaller.Mode.ADB).toList()

        val removals = executed.map { it.second }.filter { it.startsWith("rm -rf") }
        removals shouldContain "rm -rf '$SHELL_ROOT/$LEFTOVER_NAME'"
        // The root also holds the staging of every install that is extracting right now.
        removals.none { it == "rm -rf '$SHELL_ROOT'" } shouldBe true
    }

    @Test
    fun `staging that could not be removed stays sweepable`() = runTest2 {
        shellStagingChildren = listOf(SHELL_ROOT, "$SHELL_ROOT/$LEFTOVER_NAME")
        stagingRemovalFails = true
        val installer = installer()

        installer.install(plan(), AppInstaller.Mode.ADB).toList()
        installer.install(plan(), AppInstaller.Mode.ADB).toList()

        // Twice: a run whose own cleanup failed must not leave the root marked as swept.
        executed.count { it.second == "rm -rf '$SHELL_ROOT/$LEFTOVER_NAME'" } shouldBe 2
    }

    private enum class AbandonBehaviour { WORKS, THROWS }

    companion object {
        private const val SHELL_ROOT = "/data/local/tmp/butler-install"
        private const val LEFTOVER_NAME = "0f9d8f4e-0000-4000-8000-00000000cafe"
        private const val OBB_ROOT = "/storage/emulated/0/Android/obb"
        private const val OBB_DIR = "$OBB_ROOT/com.example.app"
        private const val OBB_NAME = "main.1.obb"
        private const val PARTIAL_SUFFIX = ".part"
    }
}
