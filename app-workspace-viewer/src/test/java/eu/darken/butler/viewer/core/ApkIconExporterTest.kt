package eu.darken.butler.viewer.core

import android.graphics.Bitmap
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2

/**
 * The destination check decides between writing, asking about an overwrite, and refusing outright.
 * Getting it wrong either loses a user file silently or writes through a symlink into an unrelated one.
 */
class ApkIconExporterTest : BaseTest() {

    private val target = LocalPath.build("/storage/emulated/0/Download/butler-icon.png")
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val apkArchiveParser = mockk<ApkArchiveParser>()
    private val bitmap = mockk<Bitmap>(relaxed = true)

    private fun create() = ApkIconExporter(
        apkArchiveParser = apkArchiveParser,
        gatewaySwitch = gatewaySwitch,
        dispatcherProvider = TestDispatcherProvider(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun setupGateway(exists: Boolean, fileType: FileType = FileType.FILE) {
        coEvery { gatewaySwitch.useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(gatewaySwitch)
        }
        coEvery { gatewaySwitch.exists(any()) } returns exists
        val lookup = mockk<APathLookup<APath<*>>>().apply {
            every { this@apply.fileType } returns fileType
        }
        coEvery { gatewaySwitch.lookup(any(), any()) } returns lookup
    }

    @Test
    fun `a free name is writable straight away`() = runTest2 {
        setupGateway(exists = false)

        create().inspectTarget(target) shouldBe ApkIconExporter.TargetState.FREE
    }

    @Test
    fun `an existing file is reported, not overwritten`() = runTest2 {
        setupGateway(exists = true, fileType = FileType.FILE)

        create().inspectTarget(target) shouldBe ApkIconExporter.TargetState.EXISTS_FILE
    }

    /** Writing "into" a directory would never produce the file the user asked for. */
    @Test
    fun `an existing directory is distinguished from a file`() = runTest2 {
        setupGateway(exists = true, fileType = FileType.DIRECTORY)

        create().inspectTarget(target) shouldBe ApkIconExporter.TargetState.EXISTS_OTHER
    }

    /** Replacing a symlink follows it and truncates its target, which is not what was agreed to. */
    @Test
    fun `a symlink is never treated as a replaceable file`() = runTest2 {
        setupGateway(exists = true, fileType = FileType.SYMBOLIC_LINK)

        create().inspectTarget(target) shouldBe ApkIconExporter.TargetState.EXISTS_OTHER
    }

    @Test
    fun `an unclassifiable entry is refused rather than replaced`() = runTest2 {
        setupGateway(exists = true, fileType = FileType.UNKNOWN)

        create().inspectTarget(target) shouldBe ApkIconExporter.TargetState.EXISTS_OTHER
    }

    @Test
    fun `a free destination is written without asking`() {
        decideIconSave(target, ApkIconExporter.TargetState.FREE) shouldBe IconSaveDecision.Write(target)
    }

    /** The one branch that can destroy a user's file: it must never resolve to a silent write. */
    @Test
    fun `an occupied destination asks before replacing`() {
        decideIconSave(target, ApkIconExporter.TargetState.EXISTS_FILE) shouldBe IconSaveDecision.Confirm(target)
    }

    @Test
    fun `a non-file destination is refused outright`() {
        val decision = decideIconSave(target, ApkIconExporter.TargetState.EXISTS_OTHER)

        decision.shouldBeInstanceOf<IconSaveDecision.Reject>()
        decision.error.shouldBeInstanceOf<ViewerIconTargetNotAFileException>()
    }

    /**
     * The gap between classifying the destination and committing spans a picker and a dialog. A file
     * that appears in that window was never the one the user authorised losing.
     */
    @Test
    fun `a file that appears after the check is not overwritten`() = runTest2 {
        setupGateway(exists = true, fileType = FileType.FILE)

        shouldThrow<ViewerIconTargetAppearedException> {
            create().save(bitmap, target, overwriteAuthorized = false)
        }

        coVerify(exactly = 0) { gatewaySwitch.file(any(), any()) }
    }

    @Test
    fun `a non-file destination is refused at commit time too`() = runTest2 {
        setupGateway(exists = true, fileType = FileType.SYMBOLIC_LINK)

        shouldThrow<ViewerIconTargetNotAFileException> {
            create().save(bitmap, target, overwriteAuthorized = true)
        }

        coVerify(exactly = 0) { gatewaySwitch.file(any(), any()) }
    }

    @Test
    fun `rendering delegates to the shared archive parser`() = runTest2 {
        val apkPath = LocalPath.build("/storage/emulated/0/Download/butler.apk")
        coEvery { apkArchiveParser.loadIcon(any(), any()) } returns null

        create().render(apkPath) shouldBe null
    }
}
