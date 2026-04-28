package eu.darken.butler.common.files.local.routing

import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.common.storage.StorageVolumeX
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocalPathRoutingPolicyTest : BaseTest() {

    private lateinit var storageEnvironment: StorageEnvironment
    private lateinit var storageManager: StorageManager2
    private lateinit var policy: LocalPathRoutingPolicy

    private val noCaps get() = CapabilitySnapshot.fixed(hasRoot = false, hasAdb = false)
    private val rootCaps get() = CapabilitySnapshot.fixed(hasRoot = true, hasAdb = false)

    @Before
    fun setup() {
        storageEnvironment = mockk(relaxed = false)
        every { storageEnvironment.publicStorages } returns listOf(
            p("/storage/emulated/0"),
            p("/storage/1234-5678"),
        )
        every { storageEnvironment.publicDataDirs } returns listOf(
            p("/storage/emulated/0/Android/data"),
        )
        every { storageEnvironment.publicObbDirs } returns listOf(
            p("/storage/emulated/0/Android/obb"),
        )
        every { storageEnvironment.ourPrivateDirs } returns listOf(
            p("/data/user/0/eu.darken.butler"),
        )
        every { storageEnvironment.ourPublicDirs } returns listOf(
            p("/storage/emulated/0/Android/data/eu.darken.butler"),
        )

        storageManager = mockk {
            every { storageVolumes } returns emptyList()
        }

        policy = LocalPathRoutingPolicy(storageEnvironment, storageManager)
    }

    @Test
    fun `primary storage aliases classify identically`() = runTest {
        val sdcard = policy.classify(
            p("/sdcard/Android/data/com.other/files/item"),
            AccessIntent.Read,
            rootCaps,
        )
        val emulated = policy.classify(
            p("/storage/emulated/0/Android/data/com.other/files/item"),
            AccessIntent.Read,
            rootCaps,
        )

        sdcard shouldBe emulated
        sdcard shouldBe RouteDecision.Allowed(AccessMode.ROOT)
    }

    @Test
    fun `Android obb routes elevated on API 30 and denied without elevation`() = runTest {
        policy.classify(
            p("/sdcard/Android/obb/com.other/file.obb"),
            AccessIntent.Write,
            rootCaps,
        ) shouldBe RouteDecision.Allowed(AccessMode.ROOT)

        policy.classify(
            p("/sdcard/Android/obb/com.other/file.obb"),
            AccessIntent.Write,
            noCaps,
        ) shouldBe RouteDecision.Denied
    }

    @Test
    fun `own app directories stay direct under restricted public roots`() = runTest {
        policy.classify(
            p("/sdcard/Android/data/eu.darken.butler/files/cache.bin"),
            AccessIntent.Write,
            noCaps,
        ) shouldBe RouteDecision.Allowed(AccessMode.DIRECT)
    }

    @Test
    fun `normal public path routes direct`() = runTest {
        policy.classify(
            p("/sdcard/DCIM/photo.jpg"),
            AccessIntent.Write,
            noCaps,
        ) shouldBe RouteDecision.Allowed(AccessMode.DIRECT)
    }

    @Test
    fun `removable public path routes isolated`() = runTest {
        val removable = mockk<StorageVolumeX> {
            every { isRemovable } returns true
            every { directory } returns File("/storage/1234-5678")
            every { path } returns "/storage/1234-5678"
        }
        every { storageManager.storageVolumes } returns listOf(removable)

        policy.classify(
            p("/storage/1234-5678/DCIM/photo.jpg"),
            AccessIntent.Read,
            noCaps,
        ) shouldBe RouteDecision.Allowed(AccessMode.ISOLATED)
    }

    @Test
    fun `classify direct route does not invoke capability providers`() = runTest {
        var rootCalled = 0
        var adbCalled = 0
        val lazyCaps = CapabilitySnapshot(
            rootProvider = { rootCalled++; false },
            adbProvider = { adbCalled++; false },
        )

        policy.classify(
            p("/sdcard/Download/file.txt"),
            AccessIntent.Write,
            lazyCaps,
        ) shouldBe RouteDecision.Allowed(AccessMode.DIRECT)

        rootCalled shouldBe 0
        adbCalled shouldBe 0
    }

    @Test
    fun `proactive children exposes restricted public boundaries`() {
        policy.proactiveChildren(p("/sdcard")) shouldContainExactlyInAnyOrder setOf(
            p("/sdcard/Android/data"),
            p("/sdcard/Android/obb"),
        )
    }

    @Test
    fun `whole public root is not batchable because boundaries exist`() = runTest {
        val result = policy.batchEligibility(
            BatchEligibilityRequest(
                operation = BatchOperation.COPY,
                sourceRoot = p("/sdcard"),
                sourceIntent = AccessIntent.Read,
                destinationRoot = p("/sdcard-backup"),
                destinationIntent = AccessIntent.Write,
                sourceRoute = Route(AccessMode.ROOT, mockOps(), fakeBatch()),
                destinationRoute = Route(AccessMode.ROOT, mockOps(), fakeBatch()),
                options = Any(),
            )
        )

        result.shouldBeInstanceOf<BatchEligibility.Ineligible>()
    }

    @Test
    fun `restricted subtree can co-route to public destination with ownership fixup`() = runTest {
        val owner = Ownership(1023, 1023, "media_rw", "media_rw")
        val destinationOps = mockOps { path ->
            if (path.path == "/sdcard/Backup/AndroidData") {
                LocalPathLookup.unknown(path)
            } else {
                lookup(path, FileType.DIRECTORY, owner)
            }
        }

        val result = policy.batchEligibility(
            BatchEligibilityRequest(
                operation = BatchOperation.COPY,
                sourceRoot = p("/sdcard/Android/data"),
                sourceIntent = AccessIntent.Read,
                destinationRoot = p("/sdcard/Backup/AndroidData"),
                destinationIntent = AccessIntent.Write,
                sourceRoute = Route(AccessMode.ROOT, mockOps(), fakeBatch()),
                destinationRoute = Route(AccessMode.DIRECT, destinationOps, null),
                options = Any(),
            )
        )

        val eligible = result.shouldBeInstanceOf<BatchEligibility.Eligible>()
        eligible.mode shouldBe AccessMode.ROOT
        eligible.destinationModeOverride shouldBe AccessMode.ROOT
        eligible.ownershipFixup shouldBe OwnershipFixup.InheritNearestExistingDestinationOwner(owner)
    }

    private fun p(path: String): LocalPath = LocalPath.build(path)

    private fun lookup(
        path: LocalPath,
        type: FileType,
        owner: Ownership? = null,
    ): LocalPathLookup = LocalPathLookup(
        lookedUp = path,
        fileType = type,
        size = 0L,
        modifiedAt = Instant.fromEpochMilliseconds(0),
        ownership = owner,
    )

    private fun mockOps(
        lookupAnswer: (LocalPath) -> LocalPathLookup = { lookup(it, FileType.UNKNOWN) },
    ): FileSystemOps<LocalPath, LocalPathLookup> {
        val ops = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true)
        coEvery { ops.lookup(any(), any<LookupOptions>()) } answers {
            lookupAnswer(firstArg())
        }
        return ops
    }

    private fun fakeBatch(): ClientBatchOps = mockk {
        coEvery { copySubtreeExact(any(), any(), any(), any()) } returns emptyFlow()
        coEvery { moveSubtreeExact(any(), any(), any(), any()) } returns emptyFlow()
        coEvery { deleteSubtree(any(), any()) } returns emptyFlow()
    }
}
