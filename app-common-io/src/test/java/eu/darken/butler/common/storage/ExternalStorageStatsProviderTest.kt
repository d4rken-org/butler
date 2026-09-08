package eu.darken.butler.common.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserManager2
import eu.darken.butler.common.user.UserProfile2
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException
import kotlin.time.Clock
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExternalStorageStatsProviderTest : BaseTest() {
    private val primary = LocalPath.build("/storage/emulated/10")
    private val user = UserHandle2(10)
    private val uuid = Uuid.parse("12345678-1234-1234-1234-123456789abc")
    private val context = mockk<Context>()
    private val environment = mockk<StorageEnvironment> {
        coEvery { getPublicPrimaryStorage(user) } returns primary
    }
    private val volume = mockk<StorageVolumeX> {
        every { isPrimary } returns true
        every { isEmulated } returns true
    }
    private val storage = mockk<StorageManager2> {
        every { getStorageVolume(primary.file) } returns volume
        every { getUuidForPath(primary.file) } returns uuid
    }
    private val users = mockk<UserManager2> {
        coEvery { currentUser() } returns UserProfile2(handle = user, label = "Current")
    }
    private val gateway = mockk<GatewaySwitch> {
        coEvery { canonicalize(any()) } coAnswers { firstArg() }
    }
    private val stats = mockk<StorageStatsManager>()

    @Before fun grantUsageAccess() {
        mockkObject(Permission.PACKAGE_USAGE_STATS)
        mockkObject(Permission.MANAGE_EXTERNAL_STORAGE)
        every { Permission.PACKAGE_USAGE_STATS.isGranted(context) } returns true
        every { Permission.MANAGE_EXTERNAL_STORAGE.isGranted(context) } returns true
    }

    @After fun restorePermission() = unmockkObject(Permission.PACKAGE_USAGE_STATS, Permission.MANAGE_EXTERNAL_STORAGE)

    @Test fun `verified alias keeps the scanned path and resolves the correct user and UUID`() = runTest {
        val alias = LocalPath.build("/sdcard")
        coEvery { gateway.canonicalize(alias) } returns primary
        val provider = ExternalStorageStatsProvider(context, environment, storage, users, gateway, stats, TestDispatcherProvider(), Clock.System)
        provider.prepare(alias) shouldBe ExternalStorageStatsProvider.Target(alias, uuid, user, primary)
    }

    @Test fun `a subtree or another user is not a whole current storage scan`() = runTest {
        val provider = ExternalStorageStatsProvider(context, environment, storage, users, gateway, stats, TestDispatcherProvider(), Clock.System)
        provider.prepare(primary.child("Android")) shouldBe null
        provider.prepare(LocalPath.build("/storage/emulated/0")) shouldBe null
        verify(exactly = 0) { storage.getUuidForPath(any()) }
    }

    @Test fun `physical storage is not eligible`() = runTest {
        every { volume.isEmulated } returns false
        val provider = ExternalStorageStatsProvider(context, environment, storage, users, gateway, stats, TestDispatcherProvider(), Clock.System)
        provider.prepare(primary) shouldBe null
    }

    @Test fun `missing usage access skips the optional estimate`() = runTest {
        every { Permission.PACKAGE_USAGE_STATS.isGranted(context) } returns false
        val provider = ExternalStorageStatsProvider(context, environment, storage, users, gateway, stats, TestDispatcherProvider(), Clock.System)
        provider.prepare(primary) shouldBe null
        verify(exactly = 0) { storage.getUuidForPath(any()) }
    }

    @Test fun `UUID resolution failure cannot silently select default storage`() = runTest {
        every { storage.getUuidForPath(primary.file) } throws IOException("unresolved")
        val provider = ExternalStorageStatsProvider(context, environment, storage, users, gateway, stats, TestDispatcherProvider(), Clock.System)
        shouldThrow<IOException> { provider.prepare(primary) }
    }

    @Test fun `usage access alone cannot make a silently filtered listing complete`() = runTest {
        every { Permission.MANAGE_EXTERNAL_STORAGE.isGranted(context) } returns false
        val provider = ExternalStorageStatsProvider(context, environment, storage, users, gateway, stats, TestDispatcherProvider(), Clock.System)
        provider.prepare(primary) shouldBe null
        verify(exactly = 0) { storage.getUuidForPath(any()) }
    }
}
