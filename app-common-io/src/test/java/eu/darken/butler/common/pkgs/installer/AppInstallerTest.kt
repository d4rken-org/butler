package eu.darken.butler.common.pkgs.installer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
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
    private var writeSucceeds = true
    private var sessionCreateOutput = listOf("Success: created install session [42]")
    private var sessionCreateErrors = emptyList<String>()

    /** The mode whose `pm install-create` answers success in a shape the installer cannot read. */
    private var unreadableCreateMode: ShellOps.Mode? = null

    /** What the shell staging root holds, the root itself included when it exists. */
    private var shellStagingChildren = emptyList<String>()

    /** Makes the removal of a run's own staging directory fail, leaving it behind. */
    private var stagingRemovalFails = false

    private val shellOps = mockk<ShellOps>()
    private val gatewaySwitch = mockk<GatewaySwitch>()

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
                    commitSucceeds -> ShellOpsResult(0, listOf("Success"), emptyList())
                    else -> ShellOpsResult(1, emptyList(), listOf("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
                }

                line.startsWith("rm -rf") -> when {
                    stagingRemovalFails && !line.contains(LEFTOVER_NAME) ->
                        ShellOpsResult(1, emptyList(), listOf("rm: Permission denied"))

                    else -> ShellOpsResult(0, emptyList(), emptyList())
                }

                else -> ShellOpsResult(0, emptyList(), emptyList())
            }
        }

        coEvery { gatewaySwitch.openInputStream(any()) } answers { firstArg<LocalPath>().file.inputStream() }
        coEvery { gatewaySwitch.createDir(any(), any()) } answers { gatewayWrites += firstArg<LocalPath>().path }
        coEvery { gatewaySwitch.createFile(any(), any()) } answers { gatewayWrites += firstArg<LocalPath>().path }
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } answers { ByteArrayOutputStream() }
        coEvery { gatewaySwitch.exists(any()) } answers { firstArg<LocalPath>().path in shellStagingChildren }
        coEvery { gatewaySwitch.listFiles(any()) } answers {
            val parent = firstArg<LocalPath>().path
            shellStagingChildren.filter { it != parent }.map { LocalPath.build(it) }
        }
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
        archiveService = mockk<ArchiveService>(),
        apkArchiveParser = mockk<ApkArchiveParser>(),
        storageEnvironment = mockk<StorageEnvironment>(relaxed = true),
        statusRelay = AppInstallStatusRelay(),
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

    private fun pmCommands() = executed.map { it.second }.filter { it.startsWith("pm ") }

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

    companion object {
        private const val SHELL_ROOT = "/data/local/tmp/butler-install"
        private const val LEFTOVER_NAME = "0f9d8f4e-0000-4000-8000-00000000cafe"
    }
}
