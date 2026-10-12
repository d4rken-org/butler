package eu.darken.butler.saver.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.saver.core.operations.SaveFilesOperation
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * A bundle source is saved as a ZIP of its members. Asserted on the command that reaches the
 * operation, i.e. on what is written to disk, and on the source info the UI shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaverWorkspaceSourceBundlesTest {

    private val commandSlot = slot<SaveFilesOperation.Command>()

    private val operationsManager = mockk<OperationsManager> {
        every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
        coEvery { submitManaged(any()) } returns mockk<ManagedOperation> {
            every { id } returns Operation.Id()
            every { state } returns MutableStateFlow(
                Operation.State.Queued(startedAt = Instant.fromEpochSeconds(0))
            )
        }
    }

    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context> { every { contentResolver } returns resolver }

    private val unreadableMembers = mutableSetOf<String>()

    /** @param size null when the provider cannot stat the member */
    private fun member(uri: String, size: Long? = 10L): SaverArguments.BundleMember {
        every { resolver.openInputStream(Uri.parse(uri)) } answers {
            if (uri in unreadableMembers) throw SecurityException("revoked")
            ByteArrayInputStream(byteArrayOf(1))
        }
        if (size != null) {
            val descriptor = mockk<ParcelFileDescriptor> {
                every { statSize } returns size
                every { close() } just Runs
            }
            every { resolver.openFileDescriptor(Uri.parse(uri), "r") } returns descriptor
        } else {
            every { resolver.openFileDescriptor(Uri.parse(uri), "r") } throws FileNotFoundException(uri)
        }
        return SaverArguments.BundleMember(uri = uri, entryName = uri.substringAfterLast('/'))
    }

    private fun makeWorkspace(
        uris: List<String>,
        bundles: List<List<SaverArguments.BundleMember>>,
        unreadableUris: Set<String> = emptySet(),
    ): SaverWorkspace {
        val contentUriHelper = spyk(ContentUriHelper(context))
        every { contentUriHelper.extractInfo(any()) } answers {
            val uri = firstArg<Uri>()
            if (uri.toString() in unreadableUris) throw IllegalStateException("unreadable")
            ContentUriHelper.SourceInfo(
                uri = uri,
                displayName = uri.lastPathSegment!!,
                size = 4L,
                mimeType = "application/vnd.android.package-archive",
                isAccessible = true,
            )
        }
        return SaverWorkspace(
            id = Workspace.Id(),
            arguments = SaverArguments.Default(
                sourceUris = uris,
                sourceBundles = bundles,
                destinationPath = LocalPath.build("/save"),
            ),
            dispatcherProvider = TestDispatcherProvider(),
            contentUriHelper = contentUriHelper,
            operationsManager = operationsManager,
            issueHandler = mockk<IssueHandler>(relaxed = true),
            saveFilesOperationFactory = mockk<SaveFilesOperation.Factory> {
                every { create(any(), capture(commandSlot)) } returns mockk<SaveFilesOperation>(relaxed = true)
            },
            pkgOps = mockk<PkgOps>(relaxed = true),
            json = mockk<Json>(relaxed = true),
            storageEnvironment = mockk<StorageEnvironment>(relaxed = true),
        )
    }

    private fun savedEntries(): List<Pair<String, List<String>>> =
        commandSlot.captured.sources.map { source -> source.uri.toString() to source.bundleMembers.map { it.entryName } }

    @Test
    fun `a source that cannot be read does not shift the bundles behind it`() = runTest {
        val workspace = makeWorkspace(
            uris = listOf("file:///data/app/a/base.apk", "file:///data/app/b/base.apk", "file:///data/app/c/base.apk"),
            bundles = listOf(
                emptyList(),
                listOf(member("file:///data/app/b/base.apk"), member("file:///data/app/b/split_b.apk")),
                listOf(member("file:///data/app/c/base.apk"), member("file:///data/app/c/split_c.apk")),
            ),
            unreadableUris = setOf("file:///data/app/b/base.apk"),
        )

        workspace.save()

        savedEntries() shouldBe listOf(
            "file:///data/app/a/base.apk" to emptyList<String>(),
            "file:///data/app/c/base.apk" to listOf("base.apk", "split_c.apk"),
        )
        commandSlot.captured.sources[1].bundleMembers.map { it.uri.toString() } shouldBe listOf(
            "file:///data/app/c/base.apk",
            "file:///data/app/c/split_c.apk",
        )
    }

    @Test
    fun `a single bundle saved under a custom name keeps its members`() = runTest {
        val workspace = makeWorkspace(
            uris = listOf("file:///data/app/a/base.apk"),
            bundles = listOf(listOf(member("file:///data/app/a/base.apk"), member("file:///data/app/a/split_a.apk"))),
        )
        workspace.updateFilename("Alpha_com.alpha_1.apks")

        workspace.save()

        val source = commandSlot.captured.sources.single()
        source.filename shouldBe "Alpha_com.alpha_1.apks"
        source.bundleMembers.map { it.entryName } shouldBe listOf("base.apk", "split_a.apk")
        source.size shouldBe 20L
    }

    @Test
    fun `a restored tab keeps its bundles`() = runTest {
        val bundles = listOf(listOf(member("file:///data/app/a/base.apk"), member("file:///data/app/a/split_a.apk")))
        val workspace = makeWorkspace(uris = listOf("file:///data/app/a/base.apk"), bundles = bundles)

        val restored = workspace.createArguments() as SaverArguments.Default

        restored.sourceBundles shouldBe bundles
    }

    @Test
    fun `a bundle is inaccessible while any member is unreadable`() = runTest {
        val split = "file:///data/app/a/split_a.apk"
        unreadableMembers += split
        val workspace = makeWorkspace(
            uris = listOf("file:///data/app/a/base.apk"),
            bundles = listOf(listOf(member("file:///data/app/a/base.apk"), member(split))),
        )

        workspace.sourceInfos.first().single().isAccessible shouldBe false

        unreadableMembers -= split
        workspace.refreshSourceAccessibility()
        workspace.sourceInfos.first().single().isAccessible shouldBe true

        unreadableMembers += split
        workspace.refreshSourceAccessibility()
        workspace.sourceInfos.first().single().isAccessible shouldBe false
    }

    @Test
    fun `a member of unknown size keeps the source with an unknown size`() = runTest {
        val workspace = makeWorkspace(
            uris = listOf("file:///data/app/a/base.apk"),
            bundles = listOf(
                listOf(member("file:///data/app/a/base.apk"), member("file:///data/app/a/split_a.apk", size = null)),
            ),
        )

        val info = workspace.sourceInfos.first().single()
        info.size shouldBe null
        info.isAccessible shouldBe true
        info.bundleMembers.map { it.size } shouldBe listOf(10L, null)
    }
}
